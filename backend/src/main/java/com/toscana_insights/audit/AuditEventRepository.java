package com.toscana_insights.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable page);
    long countByWorkspaceId(UUID workspaceId);
    long countByWorkspaceIdAndActionAndCreatedAtAfter(UUID workspaceId, String action, Instant after);
}
