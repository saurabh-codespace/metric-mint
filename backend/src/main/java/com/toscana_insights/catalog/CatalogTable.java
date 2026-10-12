package com.toscana_insights.catalog;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A queryable table in the data catalogue. physicalName is imp_* for imported data or schema.table for a remote database. */
@Entity
@Table(name = "app_catalog_tables", schema = "toscana_insights")
public class CatalogTable extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false) private UUID sourceId;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 200) private String physicalName;
    @Column(nullable = false, length = 500) private String description = "";
    @Column(nullable = false, length = 120) private String ownerName = "";
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20000) private String columnsJson;
    @Column(nullable = false) private long rowCount;
    private Instant lastLoadedAt;

    protected CatalogTable() {}
    public CatalogTable(UUID workspaceId, UUID sourceId, String name, String physicalName, String columnsJson) {
        this.workspaceId = workspaceId; this.sourceId = sourceId; this.name = name; this.physicalName = physicalName; this.columnsJson = columnsJson;
    }
    public void describe(String description, String ownerName, String columnsJson) {
        this.description = description == null ? "" : description; this.ownerName = ownerName == null ? "" : ownerName;
        if (columnsJson != null) this.columnsJson = columnsJson;
    }
    public void loaded(String columnsJson, long rowCount) { this.columnsJson = columnsJson; this.rowCount = rowCount; this.lastLoadedAt = Instant.now(); }

    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getSourceId() { return sourceId; }
    public String getName() { return name; }
    public String getPhysicalName() { return physicalName; }
    public String getDescription() { return description; }
    public String getOwnerName() { return ownerName; }
    public String getColumnsJson() { return columnsJson; }
    public long getRowCount() { return rowCount; }
    public Instant getLastLoadedAt() { return lastLoadedAt; }
}
