package com.toscana_insights.report;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    long countByFavoriteTrue();
    long countByWorkspaceId(UUID workspaceId);
    List<Report> findByWorkspaceIdOrderByName(UUID workspaceId);
    Optional<Report> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
