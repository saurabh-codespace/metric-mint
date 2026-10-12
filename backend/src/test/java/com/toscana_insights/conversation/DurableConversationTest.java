package com.toscana_insights.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.analytics.AnalyticsAnswerService;
import com.toscana_insights.analytics.AnalyticsController;
import com.toscana_insights.common.BudgetExceededException;
import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.workspace.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = "app.ai.provider=disabled")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DurableConversationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkspaceRepository workspaces;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired QuestionRepository questions;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AnalyticsAnswerService answers;

    private UUID workspace() {
        return workspaces.save(new Workspace("Chat test", "chat-" + UUID.randomUUID())).getId();
    }

    private RequestPostProcessor user(UUID workspace, Role role) {
        String name = "chat-" + UUID.randomUUID();
        users.save(new AppUser(workspace, name, "Chat tester", encoder.encode("test-chat-password"), role));
        return httpBasic(name, "test-chat-password");
    }

    private JsonNode postJson(String path, Object body, RequestPostProcessor actor, int expected) throws Exception {
        String result = mvc.perform(post(path).with(actor).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return result.isBlank() ? json.nullNode() : json.readTree(result);
    }

    private JsonNode getJson(String path, RequestPostProcessor actor) throws Exception {
        return json.readTree(mvc.perform(get(path).with(actor)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID conversation(RequestPostProcessor actor) throws Exception {
        return UUID.fromString(postJson("/api/conversations", Map.of("title", "A saved chat"), actor, 201).get("id").asText());
    }

    private String messages(UUID id) { return "/api/conversations/" + id + "/questions"; }

    private AnalyticsController.AskResponse response(QuerySpec query) {
        Instant at = Instant.parse("2026-10-12T10:00:00Z");
        QueryService.Lineage lineage = new QueryService.Lineage("SELECT permitted_summary", List.of(), "Approved source", List.of("orders"),
                "SUM(amount)", 1, "APPROVED", true, at, List.of("region = APAC"));
        return new AnalyticsController.AskResponse("A complete answer".repeat(200), "bar", List.of(Map.of("region", "APAC", "revenue", 250)),
                "Calculated explanation", null, "VERIFIED", List.of("Viewer row policy applied"), lineage,
                List.of("APAC revenue is 250"), true, query, at);
    }

    private QuerySpec query() { return new QuerySpec("revenue", List.of("region"), null, null, null, null, null, null); }

    @Test
    void projectsAndConversationsRemainPrivateEvenFromOtherAdministrators() throws Exception {
        UUID workspace = workspace();
        RequestPostProcessor owner = user(workspace, Role.VIEWER);
        RequestPostProcessor sameWorkspaceAdmin = user(workspace, Role.ADMIN);
        RequestPostProcessor outsideAdmin = user(workspace(), Role.ADMIN);
        JsonNode project = postJson("/api/projects", Map.of("name", "  Forecast  "), owner, 201);
        assertThat(project.path("name").asText()).isEqualTo("Forecast");
        JsonNode chat = postJson("/api/conversations", Map.of("title", "  Project discussion  ", "projectId", project.get("id").asText()), owner, 201);
        assertThat(chat.path("title").asText()).isEqualTo("Project discussion");
        assertThat(chat.get("projectId")).isEqualTo(project.get("id"));
        UUID chatId = UUID.fromString(chat.get("id").asText());
        assertThat(getJson("/api/projects", owner)).hasSize(1);
        assertThat(getJson("/api/conversations", owner).get(0).get("projectId")).isEqualTo(project.get("id"));
        for (RequestPostProcessor other : List.of(sameWorkspaceAdmin, outsideAdmin)) {
            assertThat(getJson("/api/projects", other)).isEmpty();
            assertThat(getJson("/api/conversations", other)).isEmpty();
            postJson("/api/conversations", Map.of("title", "Intrusion", "projectId", project.get("id").asText()), other, 404);
            mvc.perform(get(messages(chatId)).with(other)).andExpect(status().isNotFound());
            postJson(messages(chatId), Map.of("question", "Read private chat"), other, 404);
            mvc.perform(delete("/api/conversations/" + chatId).with(other)).andExpect(status().isNotFound());
        }
        JsonNode general = postJson("/api/conversations", Map.of("title", "General"), owner, 201);
        assertThat(general.hasNonNull("projectId")).isFalse();
        postJson("/api/projects", Map.of("name", " "), owner, 400);
        postJson("/api/projects", Map.of("name", "x".repeat(81)), owner, 400);
        postJson("/api/conversations", Map.of("title", " "), owner, 400);
        postJson(messages(chatId), Map.of("question", "x".repeat(4001)), owner, 400);
        postJson(messages(chatId), Map.of("question", " "), owner, 400);
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/conversations/" + chatId).with(owner)).andExpect(status().isNoContent());
        mvc.perform(get(messages(chatId)).with(owner)).andExpect(status().isNotFound());
        verifyNoInteractions(answers);
    }

    @Test
    void fullPromptAnswerAndEvidenceArePersistedAndReloaded() throws Exception {
        RequestPostProcessor actor = user(workspace(), Role.ANALYST);
        UUID id = conversation(actor);
        AnalyticsController.AskResponse answer = response(query());
        when(answers.ask(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<Question> pending = questions.findByConversationIdOrderByCreatedAtAsc(id);
            assertThat(pending).hasSize(1);
            assertThat(pending.getFirst().getStatus()).isEqualTo("pending");
            assertThat(pending.getFirst().getQuestionText()).hasSize(4000);
            return answer;
        });
        JsonNode created = postJson(messages(id), Map.of("question", "x".repeat(4000)), actor, 201);
        assertThat(created.path("status").asText()).isEqualTo("complete");
        assertThat(created.path("answer").asText()).isEqualTo(answer.answer()).hasSizeGreaterThan(2000);
        JsonNode reloaded = getJson(messages(id), actor).get(0);
        assertThat(reloaded).isEqualTo(created);
        assertThat(reloaded.path("rows").get(0).path("revenue").asInt()).isEqualTo(250);
        assertThat(reloaded.path("lineage").path("sql").asText()).isEqualTo("SELECT permitted_summary");
        assertThat(reloaded.path("insights").get(0).asText()).isEqualTo("APAC revenue is 250");
        assertThat(reloaded.path("aiGenerated").asBoolean()).isTrue();
        assertThat(reloaded.path("executedAt").asText()).isEqualTo(answer.executedAt().toString());
        assertThat(getJson("/api/conversations", actor).get(0).path("questionCount").asInt()).isEqualTo(1);
    }

    @Test
    void followUpsSkipClarificationsAndErrorsButOperationalSuccessClearsContext() throws Exception {
        RequestPostProcessor actor = user(workspace(), Role.ANALYST);
        UUID id = conversation(actor);
        QuerySpec query = query();
        when(answers.ask(any())).thenReturn(response(query))
                .thenReturn(new AnalyticsController.AskResponse("Clarify", "help", List.of(), "No query", "Which field?", null, List.of(), null, List.of(), false, null, null))
                .thenThrow(new IllegalStateException("private-database-error"))
                .thenReturn(response(query)).thenReturn(response(null)).thenReturn(response(null));
        for (String question : List.of("Initial", "Clarify", "Failure", "Follow-up", "Operational", "Fresh")) {
            postJson(messages(id), Map.of("question", question), actor, 201);
        }
        ArgumentCaptor<AnalyticsController.AskRequest> requests = ArgumentCaptor.forClass(AnalyticsController.AskRequest.class);
        verify(answers, times(6)).ask(requests.capture());
        assertThat(requests.getAllValues().get(0).previous()).isNull();
        for (int index : List.of(1, 2, 3, 4)) assertThat(requests.getAllValues().get(index).previous()).isEqualTo(query);
        assertThat(requests.getAllValues().get(5).previous()).isNull();
        assertThat(requests.getAllValues()).allSatisfy(r -> assertThat(r.mode()).isEqualTo("chat"));
        JsonNode history = getJson(messages(id), actor);
        assertThat(history.get(2).path("status").asText()).isEqualTo("error");
        assertThat(history.get(2).path("question").asText()).isEqualTo("Failure");
        assertThat(history.toString()).doesNotContain("private-database-error");
    }

    @Test
    void pendingMessageIsVisibleSecondPromptConflictsAndDeletionCannotResurrectChat() throws Exception {
        RequestPostProcessor actor = user(workspace(), Role.ANALYST);
        UUID id = conversation(actor);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(answers.ask(any())).thenAnswer(invocation -> {
            executing.countDown();
            assertThat(finish.await(20, TimeUnit.SECONDS)).isTrue();
            return response(query());
        });
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try { return mvc.perform(post(messages(id)).with(actor).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"In flight\"}")).andReturn(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            assertThat(executing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(getJson(messages(id), actor).get(0).path("status").asText()).isEqualTo("pending");
            postJson(messages(id), Map.of("question", "Second prompt"), actor, 409);
            assertThat(questions.countByConversationId(id)).isEqualTo(1);
            mvc.perform(delete("/api/conversations/" + id).with(actor)).andExpect(status().isNoContent());
        } finally { finish.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
        assertThat(conversations.existsById(id)).isFalse();
        assertThat(questions.countByConversationId(id)).isZero();
        verify(answers).ask(any());
    }

    @Test
    void errorsPersistSafePermissionAndBudgetMessages() throws Exception {
        RequestPostProcessor actor = user(workspace(), Role.ANALYST);
        UUID id = conversation(actor);
        when(answers.ask(any())).thenThrow(new ForbiddenException("private-permission-details"))
                .thenThrow(new BudgetExceededException("private-query-budget-details"));
        JsonNode forbidden = postJson(messages(id), Map.of("question", "Restricted"), actor, 201);
        assertThat(forbidden.path("status").asText()).isEqualTo("error");
        assertThat(forbidden.path("error").asText()).contains("permission").doesNotContain("private-permission-details");
        JsonNode budget = postJson(messages(id), Map.of("question", "Budget"), actor, 201);
        assertThat(budget.path("error").asText()).contains("query limit").doesNotContain("private-query-budget-details");
        assertThat(getJson(messages(id), actor)).hasSize(2);
    }

    @Test
    void startupRecoveryRetainsInterruptedPromptsAndLeavesCurrentRequestsAlone() throws Exception {
        RequestPostProcessor actor = user(workspace(), Role.ANALYST);
        UUID id = conversation(actor);
        String empty = "{\"rows\":[],\"warnings\":[]}";
        Question interrupted = questions.saveAndFlush(new Question(id, "Interrupted question", empty));
        Question active = questions.saveAndFlush(new Question(id, "Current request", empty));
        jdbc.update("UPDATE toscana_insights.app_questions SET created_at = ? WHERE id = ?", Instant.parse("2000-01-01T00:00:00Z"), interrupted.getId());
        service.recoverInterruptedQuestions();
        assertThat(questions.findById(interrupted.getId()).orElseThrow().getStatus()).isEqualTo("error");
        assertThat(questions.findById(interrupted.getId()).orElseThrow().getError()).contains("server restarted");
        assertThat(questions.findById(interrupted.getId()).orElseThrow().getQuestionText()).isEqualTo("Interrupted question");
        assertThat(questions.findById(active.getId()).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(getJson(messages(id), actor).get(0).path("insights")).isEmpty();
    }
}
