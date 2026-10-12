package com.toscana_insights.conversation;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_projects", schema = "toscana_insights")
public class Project extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, length = 200) private String ownerName;
    @Column(nullable = false, length = 80) private String name;

    protected Project() {}
    public Project(UUID workspaceId, String ownerName, String name) {
        this.workspaceId = workspaceId; this.ownerName = ownerName; this.name = name;
    }
    public String getName() { return name; }
}
