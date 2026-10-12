package com.toscana_insights.publication;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_published_dashboards", schema = "toscana_insights")
public class PublishedDashboard extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, length = 200) private String ownerName;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 20) private String kind;
    @Column(length = 40) private String reportId;
    @Column(nullable = false) private int days;
    @Column(nullable = false, length = 30) private String unit;
    @Column(nullable = false, length = 20) private String chart;
    @Column(nullable = false, unique = true, length = 43) private String token;
    @Column(columnDefinition = "nvarchar(max)") private String templateConfiguration;

    protected PublishedDashboard() {}

    PublishedDashboard(UUID workspaceId, String ownerName, PublicationDtos.Request definition, String token, String templateConfiguration) {
        this.workspaceId = workspaceId; this.ownerName = ownerName; this.token = token;
        this.name = definition.name(); this.kind = definition.kind(); this.reportId = definition.reportId();
        this.days = definition.days(); this.unit = definition.unit(); this.chart = definition.chart();
        this.templateConfiguration = templateConfiguration;
    }

    public String getOwnerName() { return ownerName; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getReportId() { return reportId; }
    public int getDays() { return days; }
    public String getUnit() { return unit; }
    public String getChart() { return chart; }
    public String getToken() { return token; }
    public String getTemplateConfiguration() { return templateConfiguration; }
}
