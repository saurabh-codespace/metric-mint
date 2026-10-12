package com.toscana_insights.audit;

import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditEventRepository repository;
    private final AppUserRepository users;

    public AuditService(AuditEventRepository repository, AppUserRepository users) { this.repository = repository; this.users = users; }

    /** Records an event for the actor's workspace. REQUIRES_NEW so denials and failures survive a rolled-back caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String type, Object id, String details) {
        record(users.findByUsername(actor).map(AppUser::getWorkspaceId).orElse(null), actor, action, type, id, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID workspaceId, String actor, String action, String type, Object id, String details) {
        repository.save(new AuditEvent(workspaceId, actor, action, type, id == null ? null : id.toString(), details));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recent(UUID workspaceId, int limit) {
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
    }

    @Transactional(readOnly = true)
    public long countToday(UUID workspaceId, String action) {
        return repository.countByWorkspaceIdAndActionAndCreatedAtAfter(workspaceId, action, Instant.now().truncatedTo(ChronoUnit.DAYS));
    }
}
