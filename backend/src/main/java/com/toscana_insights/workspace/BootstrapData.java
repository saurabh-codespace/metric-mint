package com.toscana_insights.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the default workspace and the demo administrator on first start. */
@Component
public class BootstrapData implements ApplicationRunner {
    private final WorkspaceRepository workspaces;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public BootstrapData(WorkspaceRepository workspaces, AppUserRepository users, PasswordEncoder encoder,
                         @Value("${app.security.demo-username}") String username,
                         @Value("${app.security.demo-password}") String password) {
        this.workspaces = workspaces; this.users = users; this.encoder = encoder; this.username = username; this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Workspace workspace = workspaces.findBySlug("default").orElseGet(() -> workspaces.save(new Workspace("Default workspace", "default")));
        String passwordHash = encoder.encode(password);
        users.findByUsername(username).ifPresentOrElse(user -> {
            user.updateCredentials("Metric Mint Administrator", passwordHash, Role.ADMIN);
            users.save(user);
        }, () -> users.save(new AppUser(workspace.getId(), username, "Metric Mint Administrator", passwordHash, Role.ADMIN)));
    }
}
