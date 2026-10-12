package com.toscana_insights.publication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishedDashboardRepository extends JpaRepository<PublishedDashboard, UUID> {
    List<PublishedDashboard> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    Optional<PublishedDashboard> findByTokenAndWorkspaceId(String token, UUID workspaceId);
    Optional<PublishedDashboard> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
