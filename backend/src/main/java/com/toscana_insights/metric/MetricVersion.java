package com.toscana_insights.metric;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only history of a metric: one row per create, edit, approval or rollback. */
@Entity
@Table(name = "app_metric_versions", schema = "toscana_insights")
public class MetricVersion extends BaseEntity {
    @Column(nullable = false) private UUID metricId;
    @Column(nullable = false) private int versionNumber;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, length = 100) private String changedBy;
    @Column(nullable = false, length = 20) private String changeType;
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 8000) private String snapshotJson;

    protected MetricVersion() {}
    public MetricVersion(UUID metricId, int versionNumber, String status, String changedBy, String changeType, String snapshotJson) {
        this.metricId = metricId; this.versionNumber = versionNumber; this.status = status; this.changedBy = changedBy;
        this.changeType = changeType; this.snapshotJson = snapshotJson;
    }
    public UUID getMetricId() { return metricId; }
    public int getVersionNumber() { return versionNumber; }
    public String getStatus() { return status; }
    public String getChangedBy() { return changedBy; }
    public String getChangeType() { return changeType; }
    public String getSnapshotJson() { return snapshotJson; }
}
