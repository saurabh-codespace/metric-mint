package com.toscana_insights.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, UUID> {
    List<ReportTemplate> findByWorkspaceIdOrderByName(UUID workspaceId);
    Optional<ReportTemplate> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
