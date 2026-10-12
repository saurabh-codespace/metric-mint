package com.toscana_insights.analytics;

import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question endpoint with optional follow-up context. Governed metrics answer first; operational summaries are a fallback for the
 * Toscana dataset; otherwise the user is asked to clarify. It never invents a number.
 */
@RestController
@RequestMapping("/api/ai")
public class AnalyticsController {
    private final AnalyticsAnswerService answers;

    public AnalyticsController(AnalyticsAnswerService answers) { this.answers = answers; }

    @PostMapping("/ask") @PreAuthorize(Roles.MEMBER)
    AskResponse ask(@Valid @RequestBody AskRequest request) { return answers.ask(request); }

    public record AskRequest(@NotBlank @Size(max = 4000) String question, String mode, QuerySpec previous) {}
    public record AskResponse(String answer, String visualization, List<?> rows, String explanation, String clarification, String label,
                              List<String> warnings, QueryService.Lineage lineage, List<String> insights, boolean aiGenerated,
                              QuerySpec query, Instant executedAt) {}
}
