package com.toscana_insights.conversation;

import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record CreateConversation(@NotBlank @Size(max = 160) String title, UUID projectId) {}
    public record AskQuestion(@NotBlank @Size(max = 4000) String question) {}
    public record ConversationResponse(UUID id, String title, Instant createdAt, long questionCount, UUID projectId) {}

    /** What is persisted for each answer: the evidence needed to re-display it, plus the spec used for follow-up questions. */
    public record StoredResult(List<?> rows, String explanation, String clarification, String label, List<String> warnings,
                               QueryService.Lineage lineage, QuerySpec spec, List<String> insights, boolean aiGenerated, Instant executedAt) {}

    public record QuestionResponse(UUID id, UUID conversationId, String question, String answer, String visualization, List<?> rows,
                                   String explanation, String clarification, String label, List<String> warnings, QueryService.Lineage lineage,
                                   QuerySpec spec, Instant createdAt, String status, String error, List<String> insights,
                                   boolean aiGenerated, Instant executedAt) {}
}
