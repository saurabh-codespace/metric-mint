package com.toscana_insights.workspace;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_workspaces", schema = "toscana_insights")
public class Workspace extends BaseEntity {
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, unique = true, length = 80) private String slug;
    @Column(nullable = false) private int maxQueriesPerDay = 500;
    @Column(nullable = false) private int maxRows = 10_000;
    @Column(nullable = false) private int queryTimeoutSeconds = 15;
    @Column(nullable = false) private int staleAfterMinutes = 1440;

    protected Workspace() {}
    public Workspace(String name, String slug) { this.name = name; this.slug = slug; }

    public void setLimits(int maxQueriesPerDay, int maxRows, int queryTimeoutSeconds, int staleAfterMinutes) {
        this.maxQueriesPerDay = maxQueriesPerDay; this.maxRows = maxRows;
        this.queryTimeoutSeconds = queryTimeoutSeconds; this.staleAfterMinutes = staleAfterMinutes;
    }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public int getMaxQueriesPerDay() { return maxQueriesPerDay; }
    public int getMaxRows() { return maxRows; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public int getStaleAfterMinutes() { return staleAfterMinutes; }
}
