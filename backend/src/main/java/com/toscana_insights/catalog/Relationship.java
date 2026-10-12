package com.toscana_insights.catalog;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A join between two catalogue tables. Queries may only use joins whose last test was SAFE. */
@Entity
@Table(name = "app_catalog_relationships", schema = "toscana_insights")
public class Relationship extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false) private UUID fromTableId;
    @Column(nullable = false, length = 128) private String fromColumn;
    @Column(nullable = false) private UUID toTableId;
    @Column(nullable = false, length = 128) private String toColumn;
    @Column(nullable = false, length = 20) private String cardinality;
    @Column(nullable = false, length = 20) private String testStatus = "UNTESTED";
    @Column(length = 500) private String testMessage;

    protected Relationship() {}
    public Relationship(UUID workspaceId, UUID fromTableId, String fromColumn, UUID toTableId, String toColumn, String cardinality) {
        this.workspaceId = workspaceId; this.fromTableId = fromTableId; this.fromColumn = fromColumn;
        this.toTableId = toTableId; this.toColumn = toColumn; this.cardinality = cardinality;
    }
    public void tested(String status, String message) { this.testStatus = status; this.testMessage = message; }

    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getFromTableId() { return fromTableId; }
    public String getFromColumn() { return fromColumn; }
    public UUID getToTableId() { return toTableId; }
    public String getToColumn() { return toColumn; }
    public String getCardinality() { return cardinality; }
    public String getTestStatus() { return testStatus; }
    public String getTestMessage() { return testMessage; }
}
