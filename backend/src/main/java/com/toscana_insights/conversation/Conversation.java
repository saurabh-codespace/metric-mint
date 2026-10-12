package com.toscana_insights.conversation;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_conversations", schema = "toscana_insights")
public class Conversation extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(length = 200) private String ownerName;
    private UUID projectId;
    @Column(nullable = false, length = 160) private String title;

    protected Conversation() {}
    public Conversation(UUID workspaceId, String ownerName, String title, UUID projectId) {
        this.workspaceId = workspaceId; this.ownerName = ownerName; this.title = title; this.projectId = projectId;
    }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getOwnerName() { return ownerName; }
    public String getTitle() { return title; }
    public UUID getProjectId() { return projectId; }
}
