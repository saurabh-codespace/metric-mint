package com.toscana_insights.audit;

import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
    private final AuditService service;
    private final CurrentUser current;

    public AuditController(AuditService service, CurrentUser current) { this.service = service; this.current = current; }

    @GetMapping
    @PreAuthorize(Roles.ADMIN_UP)
    List<AuditEvent> all(@RequestParam(defaultValue = "200") int limit) { return service.recent(current.workspaceId(), limit); }
}
