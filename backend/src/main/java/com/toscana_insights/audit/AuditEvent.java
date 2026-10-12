package com.toscana_insights.audit;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_audit_events", schema = "toscana_insights")
public class AuditEvent extends BaseEntity {
    private UUID workspaceId;
    @Column(nullable = false, length = 100)
    private String actor;
    @Column(nullable = false, length = 60)
    private String action;
    @Column(nullable = false, length = 60)
    private String resourceType;
    private String resourceId;
    @Column(length = 1000)
    private String details;

    protected AuditEvent() {}
    public AuditEvent(UUID workspaceId, String actor, String action, String resourceType, String resourceId, String details) {
        this.workspaceId = workspaceId; this.actor = actor; this.action = action; this.resourceType = resourceType; this.resourceId = resourceId;
        this.details = details == null || details.length() <= 1000 ? details : details.substring(0, 1000);
    }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetails() { return details; }
}
