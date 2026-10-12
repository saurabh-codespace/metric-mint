package com.toscana_insights.datasource;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class DataSourceDtos {
    private DataSourceDtos() {}

    /** secret is write-only: it is encrypted on arrival and never returned. config holds non-secret connector settings. */
    public record Request(@NotBlank @Size(max = 120) String name, @NotBlank String type, @Size(max = 255) String host,
                          @Min(0) @Max(65535) Integer port, @Size(max = 120) String databaseName, @Size(max = 120) String username,
                          @Size(max = 255) String credentialReference, @Size(max = 500) String secret,
                          Map<String, Object> config, @Min(1) Integer scheduleMinutes) {}

    public record Response(UUID id, String name, String type, String host, Integer port, String databaseName,
                           String username, String credentialReference, boolean hasSecret, String status,
                           Map<String, Object> config, Integer scheduleMinutes, Instant nextRunAt, Instant lastSuccessAt,
                           String lastRunStatus, String lastError, Instant lastTestedAt, String lastTestMessage,
                           boolean stale, Instant createdAt, Instant updatedAt) {}

    public record TestResult(boolean ok, String message) {}
    public record RotateSecret(@NotBlank @Size(max = 500) String secret) {}
}
