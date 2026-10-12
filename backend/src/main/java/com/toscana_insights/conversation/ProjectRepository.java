package com.toscana_insights.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByWorkspaceIdAndOwnerNameOrderByCreatedAtDesc(UUID workspaceId, String ownerName);
    boolean existsByIdAndWorkspaceIdAndOwnerName(UUID id, UUID workspaceId, String ownerName);
}
