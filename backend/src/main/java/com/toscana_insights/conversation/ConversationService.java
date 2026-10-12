package com.toscana_insights.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.analytics.AnalyticsAnswerService;
import com.toscana_insights.analytics.AnalyticsController;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.BudgetExceededException;
import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Private chat history; persist the prompt before running analytics outside the metadata transaction. */
@Service
public class ConversationService {
    private final ConversationRepository conversations;
    private final QuestionRepository questions;
    private final ProjectRepository projects;
    private final AnalyticsAnswerService answers;
    private final AuditService audit;
    private final CurrentUser current;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Instant startupTime = Instant.now();

    public ConversationService(ConversationRepository conversations, QuestionRepository questions, ProjectRepository projects,
            AnalyticsAnswerService answers, AuditService audit, CurrentUser current, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.conversations = conversations; this.questions = questions; this.projects = projects; this.answers = answers;
        this.audit = audit; this.current = current; this.mapper = mapper; this.transaction = new TransactionTemplate(transactions);
    }

    @Transactional(readOnly = true)
    public List<ConversationDtos.ConversationResponse> all() {
        AppUser user = current.user();
        return conversations.findByWorkspaceIdAndOwnerNameOrderByCreatedAtDesc(user.getWorkspaceId(), user.getUsername()).stream().map(this::view).toList();
    }

    @Transactional
    public ConversationDtos.ConversationResponse create(ConversationDtos.CreateConversation request, String actor) {
        AppUser user = current.user();
        if (request.projectId() != null && !projects.existsByIdAndWorkspaceIdAndOwnerName(request.projectId(), user.getWorkspaceId(), user.getUsername())) {
            throw new NotFoundException("Project", request.projectId());
        }
        Conversation saved = conversations.save(new Conversation(user.getWorkspaceId(), user.getUsername(), request.title().strip(), request.projectId()));
        audit.record(actor, "CREATE", "CONVERSATION", saved.getId(), saved.getTitle());
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<ConversationDtos.QuestionResponse> questions(UUID id) {
        get(id);
        return questions.findByConversationIdOrderByCreatedAtAsc(id).stream().map(this::toResponse).toList();
    }

    public ConversationDtos.QuestionResponse ask(UUID id, ConversationDtos.AskQuestion request, String actor) {
        AppUser user = current.user();
        Pending pending = transaction.execute(tx -> {
            lock(id, user);
            List<Question> history = questions.findByConversationIdOrderByCreatedAtAsc(id);
            if (history.stream().anyMatch(q -> "pending".equals(q.getStatus()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A response is already in progress in this chat");
            }
            QuerySpec previous = null;
            for (Question q : history) {
                if (!"complete".equals(q.getStatus())) continue;
                ConversationDtos.StoredResult stored = read(q);
                if (stored.clarification() == null) previous = stored.spec();
            }
            Question prompt = questions.saveAndFlush(new Question(id, request.question(), write(emptyResult())));
            return new Pending(prompt.getId(), previous);
        });

        AnalyticsController.AskResponse answer = null;
        String resultJson = null;
        String error = null;
        try {
            answer = answers.ask(new AnalyticsController.AskRequest(request.question(), "chat", pending.previous()));
            resultJson = write(new ConversationDtos.StoredResult(answer.rows(), answer.explanation(), answer.clarification(), answer.label(),
                    answer.warnings(), answer.lineage(), answer.query(), answer.insights(), answer.aiGenerated(), answer.executedAt()));
        } catch (RuntimeException exception) {
            error = safeError(exception);
        }
        AnalyticsController.AskResponse completed = answer;
        String stored = resultJson;
        String failure = error;
        ConversationDtos.QuestionResponse result = transaction.execute(tx -> {
            // Deletion and completion take the same lock; a deleted conversation is never recreated.
            lock(id, user);
            Question prompt = questions.findById(pending.id()).orElseThrow(() -> new NotFoundException("Question", pending.id()));
            if ("pending".equals(prompt.getStatus())) {
                if (failure != null) prompt.fail(failure);
                else prompt.complete(completed.answer(), completed.visualization(), stored);
            }
            return toResponse(prompt);
        });
        audit.record(actor, "ASK", "CONVERSATION", id, "Question " + pending.id() + ": " + result.status());
        return result;
    }

    @Transactional
    public void delete(UUID id, String actor) {
        Conversation value = lock(id, current.user());
        questions.deleteAll(questions.findByConversationIdOrderByCreatedAtAsc(id));
        conversations.delete(value);
        audit.record(actor, "DELETE", "CONVERSATION", id, value.getTitle());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedQuestions() {
        // shortcut: one backend process; use worker leases before running multiple application replicas.
        questions.findByStatusAndCreatedAtBefore("pending", startupTime)
                .forEach(q -> q.fail("The server restarted before this response finished. Your message was saved; please try again."));
    }

    private static String safeError(RuntimeException error) {
        if (error instanceof ForbiddenException || error instanceof AccessDeniedException) return "You do not have permission to query this data.";
        if (error instanceof BudgetExceededException) return "The workspace query limit has been reached. Please try again after the limit resets.";
        if (error instanceof NotFoundException) return "The requested data source or metric is no longer available to your account.";
        if (error instanceof BadRequestException) return "This question could not be run with the available data. Please revise it and try again.";
        return "The response could not be completed. Your message was saved; please try again.";
    }

    private Conversation get(UUID id) {
        AppUser user = current.user();
        return conversations.findByIdAndWorkspaceIdAndOwnerName(id, user.getWorkspaceId(), user.getUsername()).orElseThrow(() -> new NotFoundException("Conversation", id));
    }

    private Conversation lock(UUID id, AppUser user) {
        return conversations.lockOwned(id, user.getWorkspaceId(), user.getUsername()).orElseThrow(() -> new NotFoundException("Conversation", id));
    }

    private ConversationDtos.ConversationResponse view(Conversation c) {
        return new ConversationDtos.ConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), questions.countByConversationId(c.getId()), c.getProjectId());
    }

    private ConversationDtos.QuestionResponse toResponse(Question q) {
        ConversationDtos.StoredResult s = read(q);
        return new ConversationDtos.QuestionResponse(q.getId(), q.getConversationId(), q.getQuestionText(), q.getAnswerText(), q.getVisualization(), s.rows(),
                s.explanation(), s.clarification(), s.label(), s.warnings(), s.lineage(), s.spec(), q.getCreatedAt(), q.getStatus(), q.getError(),
                s.insights() == null ? List.of() : s.insights(), s.aiGenerated(), s.executedAt());
    }

    private static ConversationDtos.StoredResult emptyResult() {
        return new ConversationDtos.StoredResult(List.of(), null, null, null, List.of(), null, null, List.of(), false, null);
    }

    private ConversationDtos.StoredResult read(Question q) {
        try { return mapper.readValue(q.getResultJson(), ConversationDtos.StoredResult.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not read stored answer", e); }
    }

    private String write(ConversationDtos.StoredResult stored) {
        try { return mapper.writeValueAsString(stored); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not store answer", e); }
    }

    private record Pending(UUID id, QuerySpec previous) {}
}
