package com.toscana_insights.publication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class PublicationDtos {
    private PublicationDtos() {}

    public record Request(@NotBlank @Size(max = 120) String name,
                          @NotBlank @Size(max = 20) String kind,
                          @Size(max = 40) String reportId, Integer days,
                          @Size(max = 30) String unit, @Size(max = 20) String chart) {}

    public record Response(UUID id, String name, String kind, String reportId, int days, String unit,
                           String chart, String token, Instant publishedAt, String ownerName, boolean canRevoke) {}
}
