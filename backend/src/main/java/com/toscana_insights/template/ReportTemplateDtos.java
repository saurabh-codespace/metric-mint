package com.toscana_insights.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportTemplateDtos {
    private ReportTemplateDtos() {}

    public record Definition(@Min(1) @Max(1) int version, @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description, @NotNull @Size(min = 1, max = 12) List<@NotNull @Valid Widget> widgets) {}

    public record Widget(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Pattern(regexp = "workbook|report|connected") String sourceType,
            @NotBlank @Size(max = 100) String sourceId,
            @NotNull @Pattern(regexp = "bar|column|line|area|pie|donut|table") String chart,
            @Size(max = 128) String labelKey, @Size(max = 128) String valueKey,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 128) String> columns,
            @NotNull @Pattern(regexp = "half|full") String width) {}

    public record SavedTemplate(UUID id, Definition definition, String ownerName, boolean canEdit,
            Instant createdAt, Instant updatedAt) {}
    public record SourceOption(String type, String id, String name, boolean sample) {}
    public record SourceData(List<String> columns, List<Map<String, Object>> rows, boolean sample,
            List<String> warnings, Instant executedAt) {}
    public record WidgetResult(Widget widget, List<String> columns, List<Map<String, Object>> rows,
            boolean sample, List<String> warnings, Instant executedAt) {}
    public record RunResult(Definition definition, List<WidgetResult> widgets) {}
}
