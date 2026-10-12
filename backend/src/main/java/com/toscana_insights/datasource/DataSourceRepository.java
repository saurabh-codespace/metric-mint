package com.toscana_insights.datasource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepository extends JpaRepository<DataSourceConnection, UUID> {
    List<DataSourceConnection> findByWorkspaceIdOrderByName(UUID workspaceId);
    Optional<DataSourceConnection> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    long countByWorkspaceId(UUID workspaceId);
    List<DataSourceConnection> findByScheduleMinutesNotNullAndNextRunAtBefore(Instant now);
}
