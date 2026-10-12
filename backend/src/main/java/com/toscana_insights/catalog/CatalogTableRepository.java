package com.toscana_insights.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogTableRepository extends JpaRepository<CatalogTable, UUID> {
    List<CatalogTable> findByWorkspaceIdOrderByName(UUID workspaceId);
    Optional<CatalogTable> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<CatalogTable> findByWorkspaceIdAndSourceIdAndPhysicalName(UUID workspaceId, UUID sourceId, String physicalName);
    Optional<CatalogTable> findByWorkspaceIdAndName(UUID workspaceId, String name);
}
