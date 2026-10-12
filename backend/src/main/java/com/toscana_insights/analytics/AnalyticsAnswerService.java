package com.toscana_insights.analytics;

import com.toscana_insights.query.AskService;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.toscana.ToscanaAnalyticsService;
import com.toscana_insights.toscana.I2pInvoiceLookup;
import static com.toscana_insights.analytics.AnalyticsController.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Question endpoint with optional follow-up context. Governed metrics answer first; operational summaries are a fallback for the
 * Toscana dataset; otherwise the user is asked to clarify. It never invents a number.
 */
@Service
public class AnalyticsAnswerService {
    private static final List<String> TOSCANA_WORDS = List.of("lob", "work", "queue", "region", "status", "claims", "monthly", "volume", "completion", "invoice", "i2p", "dashboard", "performance", "ageing", "open");

    private final AskService asker;
    private final ToscanaAnalyticsService toscana;
    private final AiInsightService insights;
    private final I2pInvoiceLookup invoices;

    public AnalyticsAnswerService(AskService asker, ToscanaAnalyticsService toscana, AiInsightService insights, I2pInvoiceLookup invoices) { this.asker = asker; this.toscana = toscana; this.insights = insights; this.invoices = invoices; }

    public AskResponse ask(AskRequest request) {
        if (I2pInvoiceLookup.isDetailQuestion(request.question())) {
            return operationalResponse(request, invoices.answer(request.question()), true);
        }
        AskService.Answer answer = asker.ask(request.question(), request.previous());
        if (!answer.clarified()) {
            QueryService.Result r = answer.result();
            String viz = r.spec().dimensions().isEmpty() ? "number" : r.spec().dimensions().contains("time") && r.rows().size() > 1 ? "line" : "bar";
            return response(request, answer.answer(), viz, r.rows(), answer.interpretation(), r.label(), r.warnings(), r.lineage(), r.spec(), r.executedAt());
        }
        String q = request.question().toLowerCase(Locale.ROOT);
        if (TOSCANA_WORDS.stream().anyMatch(q::contains)) {
            ToscanaAnalyticsService.Answer legacy = toscana.answer(request.question());
            return operationalResponse(request, legacy, false);
        }
        return new AskResponse(answer.answer(), "help", List.of(), "No query was run.", answer.clarification(), null, List.of(), null, List.of(), false, null, null);
    }

    private AskResponse operationalResponse(AskRequest request, ToscanaAnalyticsService.Answer result, boolean detail) {
        if (result.clarification() != null) return new AskResponse(result.answer(), "help", List.of(), result.explanation(),
                result.clarification(), null, List.of(), null, List.of(), false, null, null);
        List<?> rows = result.rows() instanceof List<?> list ? list : List.of();
        List<String> warnings = List.of("Operational database result; not defined by a governed metric");
        // Keep row-level assignments out of the optional external insight provider.
        if (detail) return new AskResponse(result.answer(), result.visualization(), rows, result.explanation(), null,
                "EXPLORATORY", warnings, result.lineage(), List.of(), false, null, Instant.now());
        return response(request, result.answer(), result.visualization(), rows, result.explanation(), "EXPLORATORY", warnings,
                result.lineage(), null, Instant.now());
    }

    private AskResponse response(AskRequest request, String answer, String visualization, List<?> rows, String explanation,
                                 String label, List<String> warnings, QueryService.Lineage lineage, QuerySpec query, Instant executedAt) {
        if (!"dashboard".equalsIgnoreCase(request.mode()) && !"chat".equalsIgnoreCase(request.mode())) {
            return new AskResponse(answer, visualization, rows, explanation, null, label, warnings, lineage, List.of(), false, query, executedAt);
        }
        AiInsightService.Insight insight = insights.dashboard(request.question(), answer, explanation, rows, visualization);
        return new AskResponse(insight.answer(), insight.visualization(), rows, insight.explanation(), null, label, warnings, lineage, insight.insights(), insight.aiGenerated(), query, executedAt);
    }

}
