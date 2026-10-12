package com.toscana_insights.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.analytics.AiInsightService;
import com.toscana_insights.common.ForbiddenException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static com.toscana_insights.template.ReportTemplateDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateGenerationServiceTest {
    private final ReportTemplateService templates = mock(ReportTemplateService.class);
    private final AiInsightService ai = mock(AiInsightService.class);
    private final TemplateGenerationService service = new TemplateGenerationService(templates, ai);
    private static final String PROMPT = "Create an invoice dashboard with monthly volume and status distribution";

    @BeforeEach
    void sources() {
        when(templates.sources()).thenReturn(List.of(new SourceOption("connected", "invoice-intake", "Invoice Intake Trend", false),
                new SourceOption("connected", "invoice-status", "Invoice Status Distribution", false),
                new SourceOption("workbook", "queue-management", "Queue Management Dashboard", true),
                new SourceOption("workbook", "utilization", "Utilization Dashboard", true)));
        when(templates.source("connected", "invoice-intake")).thenReturn(new SourceData(List.of("period", "count"), List.of(Map.of("period", "PRIVATE_PERIOD_VALUE", "count", 948273)), false, List.of(), Instant.now()));
        when(templates.source("connected", "invoice-status")).thenReturn(new SourceData(List.of("name", "count"), List.of(Map.of("name", "PRIVATE_STATUS_VALUE", "count", 748392)), false, List.of(), Instant.now()));
        when(templates.source("workbook", "queue-management")).thenReturn(new SourceData(List.of("date", "resolved"), List.of(Map.of("date", "2026-10-09", "resolved", 42)), true, List.of(), Instant.now()));
        when(templates.source("workbook", "utilization")).thenReturn(new SourceData(List.of("team", "utilizationPercent"), List.of(Map.of("team", "A", "utilizationPercent", 75)), true, List.of(), Instant.now()));
        when(templates.runDefinition(any())).thenAnswer(call -> new RunResult(call.getArgument(0), List.of()));
    }

    private Definition liveDefinition() {
        return new Definition(1, "Invoice dashboard", "AP LOB90", List.of(
                new Widget("monthly", "Monthly", "connected", "invoice-intake", "line", "period", "count", List.of(), "half"),
                new Widget("status", "Status", "connected", "invoice-status", "bar", "name", "count", List.of(), "half")));
    }

    @Test
    void unconfiguredStarterHonorsModeSourcesAndFullWidth() {
        var live = service.generate(new TemplateGenerationService.Request(PROMPT + " full width", "live"));
        assertThat(live.aiGenerated()).isFalse();
        assertThat(live.message()).contains("not configured", "no AI generation");
        assertThat(live.result().definition().widgets()).hasSize(2).allSatisfy(widget -> {
            assertThat(widget.sourceType()).isEqualTo("connected");
            assertThat(widget.width()).isEqualTo("full");
        });
        var sample = service.generate(new TemplateGenerationService.Request("Create a dashboard with queue management and utilization", "sample"));
        assertThat(sample.result().definition().widgets()).hasSize(2).allSatisfy(widget -> assertThat(widget.sourceType()).isEqualTo("workbook"));
        verify(ai, never()).generateTemplate(anyString(), anyString(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"for January 2025", "only APAC", "exclude paid", "compare with last year", "include revenue", "latest duplicate queue assignee", "in India", "for 2020 only"})
    void unsupportedStarterConditionsNeverBecomeBroaderLayouts(String suffix) {
        var result = service.generate(new TemplateGenerationService.Request(PROMPT + " " + suffix, "live"));
        assertThat(result.result()).isNull();
        assertThat(result.clarification()).isNotBlank();
        verify(templates, never()).runDefinition(any());
    }

    @Test
    void configuredAiAcceptsParaphrasesAndReceivesMetadataWithoutRowValues() throws Exception {
        when(ai.configured()).thenReturn(true);
        when(ai.generateTemplate(anyString(), anyString(), anyList())).thenReturn(new AiInsightService.TemplatePlan(liveDefinition(), null));
        var result = service.generate(new TemplateGenerationService.Request("Show how invoice counts change over time and break them down by status", "live"));
        assertThat(result.aiGenerated()).isTrue();
        assertThat(result.result()).isNotNull();
        ArgumentCaptor<List> catalog = ArgumentCaptor.forClass(List.class);
        verify(ai).generateTemplate(anyString(), eq("live"), catalog.capture());
        String sent = new ObjectMapper().writeValueAsString(catalog.getValue());
        assertThat(sent).contains("invoice-intake", "invoice-status", "NUMBER", "period").doesNotContain("PRIVATE_PERIOD_VALUE", "PRIVATE_STATUS_VALUE", "948273", "748392", "rows", "workbook");
        assertThat(sent).contains("latest 12 available months", "12 most common");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Build a dashboard from monthly volume and status distribution reports", "Arrange monthly volume in a clear executive layout", "Make this dashboard show monthly volume", "Create monthly volume for management using only line charts"})
    void configuredAiAllowsLayoutPrepositions(String prompt) {
        when(ai.configured()).thenReturn(true);
        when(ai.generateTemplate(anyString(), anyString(), anyList())).thenReturn(new AiInsightService.TemplatePlan(liveDefinition(), null));
        assertThat(service.generate(new TemplateGenerationService.Request(prompt, "live")).result()).isNotNull();
        verify(ai).generateTemplate(eq(prompt), eq("live"), anyList());
    }

    @Test
    void rejectsInventedFieldsEvenOnTablesAndCrossModeSourcesBeforePreview() {
        when(ai.configured()).thenReturn(true);
        for (Widget widget : List.of(
                new Widget("x", "Bad", "workbook", "queue-management", "bar", "date", "resolved", List.of(), "half"),
                new Widget("x", "Bad", "connected", "invoice-intake", "table", "secret_column", null, List.of("period"), "half"))) {
            when(ai.generateTemplate(anyString(), anyString(), anyList())).thenReturn(new AiInsightService.TemplatePlan(new Definition(1, "Invalid", "", List.of(widget)), null));
            assertThat(service.generate(new TemplateGenerationService.Request("Show invoice intake trend", "live")).result()).isNull();
        }
        verify(templates, never()).runDefinition(any());
    }

    @Test
    void missingRequestedSourceAndProviderFailureDoNotSilentlyBecomeStarters() {
        when(ai.configured()).thenReturn(true);
        Definition incomplete = new Definition(1, "Only one", "", List.of(liveDefinition().widgets().getFirst()));
        when(ai.generateTemplate(anyString(), anyString(), anyList())).thenReturn(new AiInsightService.TemplatePlan(incomplete, null));
        assertThat(service.generate(new TemplateGenerationService.Request(PROMPT, "live")).result()).isNull();
        when(ai.generateTemplate(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("private-provider-error"));
        var error = service.generate(new TemplateGenerationService.Request(PROMPT, "live"));
        assertThat(error.result()).isNull();
        assertThat(error.message()).contains("retry").doesNotContain("private-provider-error");
        verify(templates, never()).runDefinition(any());
    }

    @Test
    void unrelatedInaccessibleSourceIsReportedButRequestedOneClarifies() {
        when(templates.source("connected", "invoice-status")).thenThrow(new ForbiddenException("private failure"));
        var monthly = service.generate(new TemplateGenerationService.Request("Create monthly volume dashboard", "live"));
        assertThat(monthly.result()).isNotNull();
        assertThat(monthly.message()).contains("Unavailable sources", "Invoice Status Distribution").doesNotContain("private failure");
        assertThat(service.generate(new TemplateGenerationService.Request(PROMPT, "live")).result()).isNull();
    }
}
