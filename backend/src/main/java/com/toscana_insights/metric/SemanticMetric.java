package com.toscana_insights.metric;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_semantic_metrics", schema = "toscana_insights")
public class SemanticMetric extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 80) private String keyName;
    @Column(nullable = false, length = 500) private String description;
    @Column(nullable = false, length = 30) private String aggregation;
    @Column(nullable = false, length = 255) private String sourceField;
    @Column(nullable = false) private boolean active;
    private UUID tableId;
    @Column(length = 128) private String dateColumn;
    @Column(length = 500) private String synonyms;
    @Column(precision = 38, scale = 6) private BigDecimal baselineValue;
    @Column(nullable = false, length = 20) private String status = "DRAFT";
    @Column(nullable = false) private int version = 1;
    @Column(length = 100) private String authorName;
    @Column(length = 100) private String approvedBy;
    private Instant approvedAt;
    @Column(nullable = false) private boolean verified;
    @Column(length = 100) private String verifiedBy;
    private Instant verifiedAt;

    protected SemanticMetric() {}
    public SemanticMetric(UUID workspaceId, String author) { this.workspaceId = workspaceId; this.authorName = author; }

    /** Applies an edit. Any edit produces a new DRAFT version that must be approved (and verified) again. */
    public void define(String name, String keyName, String description, String aggregation, String sourceField, boolean active,
                       UUID tableId, String dateColumn, String synonyms, BigDecimal baselineValue) {
        this.name = name; this.keyName = keyName; this.description = description; this.aggregation = aggregation; this.sourceField = sourceField;
        this.active = active; this.tableId = tableId; this.dateColumn = dateColumn; this.synonyms = synonyms; this.baselineValue = baselineValue;
    }
    public void newDraftVersion(boolean first) {
        if (!first) version++;
        status = "DRAFT"; approvedBy = null; approvedAt = null; verified = false; verifiedBy = null; verifiedAt = null;
    }
    public void approve(String by) { status = "APPROVED"; approvedBy = by; approvedAt = Instant.now(); }
    public void markVerified(String by) { verified = true; verifiedBy = by; verifiedAt = Instant.now(); }
    public void clearVerified() { verified = false; verifiedBy = null; verifiedAt = null; }
    public void restore(int newVersion, String status, String approvedBy, Instant approvedAt, boolean verified) {
        this.version = newVersion; this.status = status; this.approvedBy = approvedBy; this.approvedAt = approvedAt;
        this.verified = verified; this.verifiedBy = verified ? "rollback" : null; this.verifiedAt = verified ? Instant.now() : null;
    }

    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getKeyName() { return keyName; }
    public String getDescription() { return description; }
    public String getAggregation() { return aggregation; }
    public String getSourceField() { return sourceField; }
    public boolean isActive() { return active; }
    public UUID getTableId() { return tableId; }
    public String getDateColumn() { return dateColumn; }
    public String getSynonyms() { return synonyms; }
    public BigDecimal getBaselineValue() { return baselineValue; }
    public String getStatus() { return status; }
    public int getVersion() { return version; }
    public String getAuthorName() { return authorName; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public boolean isVerified() { return verified; }
    public String getVerifiedBy() { return verifiedBy; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
