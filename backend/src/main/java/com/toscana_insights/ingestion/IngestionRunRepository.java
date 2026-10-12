package com.toscana_insights.ingestion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {
    List<IngestionRun> findBySourceIdAndWorkspaceIdOrderByStartedAtDesc(UUID sourceId, UUID workspaceId, Pageable page);
    long countByWorkspaceIdAndStatus(UUID workspaceId, String status);
}
