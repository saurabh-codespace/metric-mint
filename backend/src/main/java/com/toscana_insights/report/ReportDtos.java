package com.toscana_insights.report;

import com.toscana_insights.query.QuerySpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ReportDtos {
    private ReportDtos() {}

    public record Request(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 30) String visualization, @NotNull @Valid QuerySpec query, boolean favorite, boolean shared) {}

    public record Response(UUID id, String name, String visualization, QuerySpec query, boolean favorite, boolean shared, String owner, boolean mine, Instant createdAt, Instant updatedAt) {}
}
