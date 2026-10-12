package com.toscana_insights.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessPolicyRepository extends JpaRepository<AccessPolicy, UUID> {
    List<AccessPolicy> findByWorkspaceId(UUID workspaceId);
    List<AccessPolicy> findByWorkspaceIdAndTableId(UUID workspaceId, UUID tableId);
    Optional<AccessPolicy> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
