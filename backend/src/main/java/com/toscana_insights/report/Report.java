package com.toscana_insights.report;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A saved report. configuration holds the QuerySpec as JSON; it is re-run with each viewer's own permissions. */
@Entity
@Table(name = "app_reports", schema = "toscana_insights")
public class Report extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(length = 100) private String ownerName;
    @Column(nullable = false) private boolean shared;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 30) private String visualization;
    @Column(nullable = false, length = 4000) private String configuration;
    @Column(nullable = false) private boolean favorite;

    protected Report() {}
    public Report(UUID workspaceId, String ownerName) { this.workspaceId = workspaceId; this.ownerName = ownerName; }
    public void update(String name, String visualization, String configuration, boolean favorite, boolean shared) {
        this.name = name; this.visualization = visualization; this.configuration = configuration; this.favorite = favorite; this.shared = shared;
    }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getOwnerName() { return ownerName; }
    public boolean isShared() { return shared; }
    public String getName() { return name; }
    public String getVisualization() { return visualization; }
    public String getConfiguration() { return configuration; }
    public boolean isFavorite() { return favorite; }
}
