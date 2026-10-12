package com.toscana_insights.ingestion;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_ingestion_runs", schema = "toscana_insights")
public class IngestionRun extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false) private UUID sourceId;
    @Column(nullable = false, length = 20) private String triggerType;
    @Column(nullable = false, length = 20) private String status = "RUNNING";
    @Column(nullable = false) private Instant startedAt = Instant.now();
    private Instant finishedAt;
    @Column(nullable = false) private long rowsLoaded;
    @Column(nullable = false) private long rowsRejected;
    @Column(length = 500) private String message;
    @Column(length = 160) private String tableName;

    protected IngestionRun() {}
    public IngestionRun(UUID workspaceId, UUID sourceId, String triggerType) { this.workspaceId = workspaceId; this.sourceId = sourceId; this.triggerType = triggerType; }

    public void finish(String status, long rowsLoaded, long rowsRejected, String tableName, String message) {
        this.status = status; this.rowsLoaded = rowsLoaded; this.rowsRejected = rowsRejected; this.tableName = tableName;
        this.message = message == null || message.length() <= 500 ? message : message.substring(0, 500);
        this.finishedAt = Instant.now();
    }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getSourceId() { return sourceId; }
    public String getTriggerType() { return triggerType; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getRowsLoaded() { return rowsLoaded; }
    public long getRowsRejected() { return rowsRejected; }
    public String getMessage() { return message; }
    public String getTableName() { return tableName; }
}
