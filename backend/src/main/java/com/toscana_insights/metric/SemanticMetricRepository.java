package com.toscana_insights.metric;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticMetricRepository extends JpaRepository<SemanticMetric, UUID> {
    long countByActiveTrue();
    long countByWorkspaceIdAndActiveTrue(UUID workspaceId);
    List<SemanticMetric> findByWorkspaceIdOrderByName(UUID workspaceId);
    Optional<SemanticMetric> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<SemanticMetric> findByWorkspaceIdAndKeyName(UUID workspaceId, String keyName);
}
