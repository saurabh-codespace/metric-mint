package com.toscana_insights.datasource;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_source_connections", schema = "toscana_insights")
public class DataSourceConnection extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 30) private String type;
    @Column(nullable = false, length = 255) private String host = "";
    @Column(nullable = false) private Integer port = 0;
    @Column(nullable = false, length = 120) private String databaseName = "";
    @Column(nullable = false, length = 120) private String username = "";
    @Column(nullable = false, length = 255) private String credentialReference = "";
    @Column(nullable = false, length = 20) private String status = "NOT_TESTED";
    @Column(length = 2000) private String secretCipher;
    @Column(length = 4000) private String configJson;
    private Instant lastTestedAt;
    @Column(length = 500) private String lastTestMessage;
    private Integer scheduleMinutes;
    private Instant nextRunAt;
    private Instant lastSuccessAt;
    @Column(length = 20) private String lastRunStatus;
    @Column(length = 500) private String lastError;
    @Column(length = 255) private String cursorValue;
    @Column(length = 64) private String lastChecksum;

    protected DataSourceConnection() {}
    public DataSourceConnection(UUID workspaceId, String name, String type) { this.workspaceId = workspaceId; this.name = name; this.type = type; }

    public void update(String name, String type, String host, Integer port, String databaseName, String username, String credentialReference, String configJson, Integer scheduleMinutes) {
        this.name = name; this.type = type; this.host = host; this.port = port; this.databaseName = databaseName;
        this.username = username; this.credentialReference = credentialReference; this.configJson = configJson;
        this.scheduleMinutes = scheduleMinutes;
        this.nextRunAt = scheduleMinutes == null ? null : Instant.now().plusSeconds(scheduleMinutes * 60L);
    }
    public void setSecretCipher(String secretCipher) { this.secretCipher = secretCipher; }
    public void testResult(boolean ok, String message) {
        this.lastTestedAt = Instant.now(); this.lastTestMessage = trim(message, 500);
        if (!"PAUSED".equals(status)) this.status = ok ? "HEALTHY" : "FAILED";
    }
    public void pause() { this.status = "PAUSED"; }
    public void resume() { this.status = "NOT_TESTED"; }
    public void runSucceeded(String cursor, String checksum) {
        this.lastRunStatus = "SUCCESS"; this.lastError = null; this.lastSuccessAt = Instant.now();
        if (cursor != null) this.cursorValue = cursor;
        if (checksum != null) this.lastChecksum = checksum;
        scheduleNext();
    }
    public void runFailed(String error) { this.lastRunStatus = "FAILED"; this.lastError = trim(error, 500); scheduleNext(); }
    private void scheduleNext() { if (scheduleMinutes != null) this.nextRunAt = Instant.now().plusSeconds(scheduleMinutes * 60L); }
    private static String trim(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }

    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getHost() { return host; }
    public Integer getPort() { return port; }
    public String getDatabaseName() { return databaseName; }
    public String getUsername() { return username; }
    public String getCredentialReference() { return credentialReference; }
    public String getStatus() { return status; }
    public boolean isPaused() { return "PAUSED".equals(status); }
    public String getSecretCipher() { return secretCipher; }
    public String getConfigJson() { return configJson; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public String getLastTestMessage() { return lastTestMessage; }
    public Integer getScheduleMinutes() { return scheduleMinutes; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public String getLastRunStatus() { return lastRunStatus; }
    public String getLastError() { return lastError; }
    public String getCursorValue() { return cursorValue; }
    public String getLastChecksum() { return lastChecksum; }
}
