package com.toscana_insights.template;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_report_templates", schema = "toscana_insights")
public class ReportTemplate extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, length = 200) private String ownerName;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, columnDefinition = "nvarchar(max)") private String definition;

    protected ReportTemplate() {}
    public ReportTemplate(UUID workspaceId, String ownerName) {
        this.workspaceId = workspaceId;
        this.ownerName = ownerName;
    }
    public void update(String name, String definition) { this.name = name; this.definition = definition; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getOwnerName() { return ownerName; }
    public String getName() { return name; }
    public String getDefinition() { return definition; }
}
