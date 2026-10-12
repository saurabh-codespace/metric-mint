package com.toscana_insights.workspace;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_users", schema = "toscana_insights")
public class AppUser extends BaseEntity {
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, unique = true, length = 200) private String username;
    @Column(nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
    @Column(nullable = false) private boolean enabled = true;

    protected AppUser() {}
    public AppUser(UUID workspaceId, String username, String displayName, String passwordHash, Role role) {
        this.workspaceId = workspaceId; this.username = username; this.displayName = displayName;
        this.passwordHash = passwordHash; this.role = role;
    }
    public void update(String displayName, Role role, boolean enabled) { this.displayName = displayName; this.role = role; this.enabled = enabled; }
    public void updateCredentials(String displayName, String passwordHash, Role role) {
        this.displayName = displayName; this.passwordHash = passwordHash; this.role = role; this.enabled = true;
    }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
}
