package com.toscana_insights.workspace;

import com.toscana_insights.common.ForbiddenException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Resolves the caller's identity, workspace and role. Every tenant-scoped query must start from workspaceId(). */
@Component
public class CurrentUser {
    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) { this.users = users; }

    public AppUser user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new ForbiddenException("Not authenticated");
        return users.findByUsername(auth.getName()).orElseThrow(() -> new ForbiddenException("Unknown user"));
    }
    public UUID workspaceId() { return user().getWorkspaceId(); }
    public String username() { return user().getUsername(); }
    public Role role() { return user().getRole(); }
    public boolean isAdmin() { return role() == Role.OWNER || role() == Role.ADMIN; }
}
