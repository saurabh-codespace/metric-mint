package com.toscana_insights.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {
    List<Relationship> findByWorkspaceId(UUID workspaceId);
    Optional<Relationship> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
