package com.toscana_insights.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.toscana_insights.query.AskService;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.toscana.ToscanaAnalyticsService;
import com.toscana_insights.toscana.I2pInvoiceLookup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsControllerTest {
    private final AskService asker = mock(AskService.class);
    private final ToscanaAnalyticsService toscana = mock(ToscanaAnalyticsService.class);
    private final AiInsightService insights = mock(AiInsightService.class);
    private final I2pInvoiceLookup invoices = mock(I2pInvoiceLookup.class);
    private final AnalyticsController controller = new AnalyticsController(new AnalyticsAnswerService(asker, toscana, insights, invoices));

    @Test
    void chatAndDashboardSummarizeOnlyExecutedRowsAndRetainEvidence() {
        QuerySpec query = new QuerySpec("revenue", List.of("region"), null, null, null, null, null, null);
        Instant executedAt = Instant.parse("2026-10-12T10:00:00Z");
        List<Map<String, Object>> rows = List.of(Map.of("region", "APAC", "revenue", 250));
        List<String> warnings = List.of("Viewer row policy applied");
        QueryService.Result result = new QueryService.Result("revenue", "Revenue", List.of("region", "revenue"), rows,
                false, "VERIFIED", warnings, null, executedAt, query);
        when(asker.ask("by region", query)).thenReturn(new AskService.Answer("Revenue is 250", "Calculated revenue", null, result));
        when(insights.dashboard("by region", "Revenue is 250", "Calculated revenue", rows, "bar"))
                .thenReturn(new AiInsightService.Insight("Summary", "Explanation", List.of("APAC revenue is 250"), "column", true));
        for (String mode : List.of("chat", "dashboard")) {
            var response = controller.ask(new AnalyticsController.AskRequest("by region", mode, query));
            assertThat(response.answer()).isEqualTo("Summary");
            assertThat(response.aiGenerated()).isTrue();
            assertThat(response.query()).isEqualTo(query);
            assertThat(response.executedAt()).isEqualTo(executedAt);
            assertThat(response.rows()).isEqualTo(rows);
            assertThat(response.warnings()).isEqualTo(warnings);
            assertThat(response.label()).isEqualTo("VERIFIED");
        }
        verify(insights, times(2)).dashboard("by region", "Revenue is 250", "Calculated revenue", rows, "bar");
        var plain = controller.ask(new AnalyticsController.AskRequest("by region", "answer", query));
        assertThat(plain.answer()).isEqualTo("Revenue is 250");
        assertThat(plain.aiGenerated()).isFalse();
        assertThat(plain.query()).isEqualTo(query);
        verifyNoMoreInteractions(insights);
        verifyNoInteractions(toscana, invoices);
    }

    @Test
    void operationalFallbackHasTimestampWithoutClaimingGovernedQuery() {
        when(asker.ask("invoice performance", null)).thenReturn(new AskService.Answer("Clarify", null, "Clarify", null));
        when(toscana.answer("invoice performance")).thenReturn(new ToscanaAnalyticsService.Answer("12 invoices", List.of(Map.of("invoices", 12)), "Operational summary"));
        Instant before = Instant.now();
        var response = controller.ask(new AnalyticsController.AskRequest("invoice performance", null, null));
        assertThat(response.query()).isNull();
        assertThat(response.executedAt()).isBetween(before, Instant.now());
        assertThat(response.label()).isEqualTo("EXPLORATORY");
        assertThat(response.warnings()).containsExactly("Operational database result; not defined by a governed metric");
        verifyNoInteractions(insights, invoices);
    }

    @Test
    void clarificationDoesNotRunInsightSummarizationOrInventContext() {
        when(asker.ask("unicorns", null)).thenReturn(new AskService.Answer("Which metric?", null, "Which metric?", null));
        var response = controller.ask(new AnalyticsController.AskRequest("unicorns", "chat", null));
        assertThat(response.clarification()).isEqualTo("Which metric?");
        assertThat(response.query()).isNull();
        assertThat(response.executedAt()).isNull();
        assertThat(response.rows()).isEmpty();
        verifyNoInteractions(insights, toscana, invoices);
    }

    @Test
    void invoiceDetailsBypassGovernedAggregatesAndExternalInsightsInEveryMode() {
        String question = "Can you pull the latest invoice number in duplicate queue and assigned to whom?";
        List<Map<String, Object>> rows = List.of(Map.of("invoiceNumber", "INV-32", "assignedTo", "test.operator", "queue", "Duplicate Check"));
        QueryService.Lineage lineage = new QueryService.Lineage("SELECT TOP (1) ...", List.of("1"), "I2P", List.of("dbo.NeonExternal"),
                "Latest I2P invoice", 0, "OPERATIONAL", false, null, List.of("lobID = 92"));
        when(invoices.answer(question)).thenReturn(new ToscanaAnalyticsService.Answer("INV-32 is assigned to test.operator", rows,
                "Live detail result", "table", lineage, null));
        QuerySpec previous = new QuerySpec("invoice_count", List.of(), null, null, null, null, null, null);
        for (String mode : List.of("chat", "dashboard", "answer")) {
            Instant before = Instant.now();
            var response = controller.ask(new AnalyticsController.AskRequest(question, mode, previous));
            assertThat(response.answer()).isEqualTo("INV-32 is assigned to test.operator");
            assertThat(response.visualization()).isEqualTo("table");
            assertThat(response.rows()).isEqualTo(rows);
            assertThat(response.lineage()).isSameAs(lineage);
            assertThat(response.label()).isEqualTo("EXPLORATORY");
            assertThat(response.warnings()).containsExactly("Operational database result; not defined by a governed metric");
            assertThat(response.query()).isNull();
            assertThat(response.executedAt()).isBetween(before, Instant.now());
            assertThat(response.aiGenerated()).isFalse();
            assertThat(response.insights()).isEmpty();
        }
        verify(invoices, times(3)).answer(question);
        verifyNoInteractions(asker, toscana, insights);
    }

    @Test
    void unsupportedInvoiceDetailsClarifyWithoutExecutionTimestampOrAggregateFallback() {
        String question = "Show the latest invoice in duplicate queue before 2026-10-01";
        when(invoices.answer(question)).thenReturn(ToscanaAnalyticsService.Answer.clarify("Additional filters are unsupported"));
        var response = controller.ask(new AnalyticsController.AskRequest(question, "chat", null));
        assertThat(response.clarification()).isEqualTo("Additional filters are unsupported");
        assertThat(response.visualization()).isEqualTo("help");
        assertThat(response.executedAt()).isNull();
        assertThat(response.query()).isNull();
        assertThat(response.lineage()).isNull();
        assertThat(response.label()).isNull();
        assertThat(response.rows()).isEmpty();
        assertThat(response.warnings()).isEmpty();
        verifyNoInteractions(asker, toscana, insights);
    }
}
