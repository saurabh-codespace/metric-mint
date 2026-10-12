package com.toscana_insights.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.report.ReportService;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.toscana_insights.template.ReportTemplateDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportTemplateServiceTest {
    @Test
    void cachesSourcesOnlyWithinEachRunAndPropagatesPermissionChanges() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            ReportService reports = mock(ReportService.class);
            ReportTemplateService service = new ReportTemplateService(null, null, null, reports, new ObjectMapper(), factory.getValidator(), null, null, null);
            UUID id = UUID.randomUUID();
            when(reports.run(id)).thenReturn(result(10)).thenReturn(result(20)).thenThrow(new ForbiddenException("Access revoked"));
            Definition definition = new Definition(1, "Live charts", "", List.of(widget("a", id, "bar"), widget("b", id, "line")));
            assertThat(service.runDefinition(definition).widgets()).allSatisfy(widget -> {
                assertThat(widget.rows().getFirst().get("amount")).isEqualTo(10);
                assertThat(widget.warnings()).contains("Source warning", "Source rows were truncated by the query limit.");
            });
            verify(reports, times(1)).run(id);
            assertThat(service.runDefinition(definition).widgets().getFirst().rows().getFirst().get("amount")).isEqualTo(20);
            assertThatThrownBy(() -> service.runDefinition(definition)).isInstanceOf(ForbiddenException.class);
            verify(reports, times(3)).run(id);
        }
    }

    @Test
    void rejectsNegativePieAndNonFiniteChartValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            ReportService reports = mock(ReportService.class);
            ReportTemplateService service = new ReportTemplateService(null, null, null, reports, new ObjectMapper(), factory.getValidator(), null, null, null);
            UUID id = UUID.randomUUID();
            when(reports.run(id)).thenReturn(result(-1)).thenReturn(result(Double.NaN));
            assertThatThrownBy(() -> service.runDefinition(new Definition(1, "Pie", "", List.of(widget("a", id, "pie")))))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("negative");
            assertThatThrownBy(() -> service.runDefinition(new Definition(1, "Line", "", List.of(widget("a", id, "line")))))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("numeric");
        }
    }

    private static Widget widget(String id, UUID sourceId, String chart) {
        return new Widget(id, "Revenue", "report", sourceId.toString(), chart, "region", "amount", List.of(), "half");
    }
    private static QueryService.Result result(Number amount) {
        return new QueryService.Result("revenue", "Revenue", List.of("region", "amount"),
                List.of(Map.of("region", "APAC", "amount", amount)), true, "Revenue", List.of("Source warning"), null, Instant.now(), null);
    }
}
