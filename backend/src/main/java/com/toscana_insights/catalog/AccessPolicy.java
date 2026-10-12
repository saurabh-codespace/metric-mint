package com.toscana_insights.catalog;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Row-level policy enforced by the query service: rows of a table are limited to those where columnName = matchValue
 * (or IN a comma-separated list) for the targeted role or user. Several policies on one table are ANDed.
 * Column-level protection uses the RESTRICTED sensitivity on catalogue columns.
 */
@Entity
@Table(name = "app_access_policies", schema = "toscana_insights")
public class AccessPolicy extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false) private UUID tableId;
    @Column(nullable = false, length = 128) private String columnName;
    @Column(nullable = false, length = 500) private String matchValue;
    @Column(length = 20) private String roleName;
    @Column(length = 200) private String targetUsername;

    protected AccessPolicy() {}
    public AccessPolicy(UUID workspaceId, UUID tableId, String columnName, String matchValue, String roleName, String targetUsername) {
        this.workspaceId = workspaceId; this.tableId = tableId; this.columnName = columnName; this.matchValue = matchValue;
        this.roleName = roleName; this.targetUsername = targetUsername;
    }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getTableId() { return tableId; }
    public String getColumnName() { return columnName; }
    public String getMatchValue() { return matchValue; }
    public String getRoleName() { return roleName; }
    public String getTargetUsername() { return targetUsername; }
}
