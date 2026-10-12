package com.toscana_insights.metric;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MetricDtos {
    private MetricDtos() {}

    /** sourceField is a column or a simple arithmetic expression of columns (e.g. amount - discount); "*" is allowed for COUNT. */
    public record Request(@NotBlank @Size(max = 120) String name, @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]*") String keyName,
                          @NotBlank @Size(max = 500) String description, @NotBlank String aggregation, @NotBlank @Size(max = 255) String sourceField,
                          boolean active, UUID tableId, String dateColumn, List<String> synonyms, BigDecimal baselineValue) {}

    public record Response(UUID id, String name, String keyName, String description, String aggregation, String sourceField, boolean active,
                           UUID tableId, String dateColumn, List<String> synonyms, BigDecimal baselineValue, String status, int version,
                           String author, String approvedBy, Instant approvedAt, boolean verified, String verifiedBy, Instant verifiedAt,
                           Instant createdAt, Instant updatedAt) {
        static Response from(SemanticMetric m) {
            List<String> synonyms = m.getSynonyms() == null || m.getSynonyms().isBlank() ? List.of() : List.of(m.getSynonyms().split("\\s*,\\s*"));
            return new Response(m.getId(), m.getName(), m.getKeyName(), m.getDescription(), m.getAggregation(), m.getSourceField(), m.isActive(), m.getTableId(),
                    m.getDateColumn(), synonyms, m.getBaselineValue(), m.getStatus(), m.getVersion(), m.getAuthorName(), m.getApprovedBy(), m.getApprovedAt(),
                    m.isVerified(), m.getVerifiedBy(), m.getVerifiedAt(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    public record Snapshot(String name, String keyName, String description, String aggregation, String sourceField, boolean active, UUID tableId,
                           String dateColumn, String synonyms, BigDecimal baselineValue, String status, int version, String approvedBy, Instant approvedAt, boolean verified) {}

    public record VersionView(int version, String status, String changeType, String changedBy, Instant at, Snapshot snapshot) {}
    public record FieldChange(String field, Object from, Object to) {}
    public record TestResult(boolean passed, BigDecimal expected, BigDecimal actual, String message) {}
}
