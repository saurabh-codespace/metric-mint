package com.toscana_insights.auth;

import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final CurrentUser current;

    public AuthController(CurrentUser current) { this.current = current; }

    @GetMapping("/me")
    AuthUser me() {
        AppUser user = current.user();
        return new AuthUser(user.getUsername(), user.getDisplayName(), List.of(user.getRole().name()), user.getWorkspaceId());
    }

    record AuthUser(String username, String displayName, List<String> roles, UUID workspaceId) {}
}
