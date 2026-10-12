package com.toscana_insights.workspace;

import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final WorkspaceRepository workspaces;
    private final AppUserRepository users;
    private final CurrentUser current;
    private final AuditService audit;
    private final PasswordEncoder encoder;

    public WorkspaceController(WorkspaceRepository workspaces, AppUserRepository users, CurrentUser current, AuditService audit, PasswordEncoder encoder) {
        this.workspaces = workspaces; this.users = users; this.current = current; this.audit = audit; this.encoder = encoder;
    }

    public record WorkspaceView(UUID id, String name, int maxQueriesPerDay, int maxRows, int queryTimeoutSeconds, int staleAfterMinutes, long queriesToday) {}
    public record Limits(@Min(1) @Max(1_000_000) int maxQueriesPerDay, @Min(1) @Max(1_000_000) int maxRows,
                         @Min(1) @Max(600) int queryTimeoutSeconds, @Min(1) int staleAfterMinutes) {}
    public record UserView(UUID id, String username, String displayName, Role role, boolean enabled) {
        static UserView from(AppUser u) { return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.isEnabled()); }
    }
    public record CreateUser(@NotBlank @Size(max = 200) String username, @NotBlank @Size(max = 120) String displayName,
                             @NotBlank @Size(min = 8, max = 72) String password, @NotNull Role role) {}
    public record UpdateUser(@NotBlank @Size(max = 120) String displayName, @NotNull Role role, boolean enabled) {}

    @GetMapping("/workspace")
    @Transactional(readOnly = true)
    public WorkspaceView workspace() {
        Workspace w = load();
        return new WorkspaceView(w.getId(), w.getName(), w.getMaxQueriesPerDay(), w.getMaxRows(), w.getQueryTimeoutSeconds(), w.getStaleAfterMinutes(),
                audit.countToday(w.getId(), "QUERY"));
    }

    @PutMapping("/workspace/limits")
    @PreAuthorize(Roles.ADMIN_UP)
    @Transactional
    public WorkspaceView limits(@Valid @RequestBody Limits limits) {
        Workspace w = load();
        w.setLimits(limits.maxQueriesPerDay(), limits.maxRows(), limits.queryTimeoutSeconds(), limits.staleAfterMinutes());
        audit.record(current.username(), "UPDATE", "WORKSPACE_LIMITS", w.getId(), limits.toString());
        return workspace();
    }

    @GetMapping("/users")
    @PreAuthorize(Roles.ADMIN_UP)
    public List<UserView> users() { return users.findByWorkspaceIdOrderByUsername(current.workspaceId()).stream().map(UserView::from).toList(); }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_UP)
    @Transactional
    public UserView createUser(@Valid @RequestBody CreateUser request) {
        if (users.findByUsername(request.username()).isPresent()) throw new BadRequestException("Username already exists");
        if (request.role() == Role.OWNER && current.role() != Role.OWNER) throw new BadRequestException("Only an owner can create owners");
        AppUser saved = users.save(new AppUser(current.workspaceId(), request.username(), request.displayName(), encoder.encode(request.password()), request.role()));
        audit.record(current.username(), "CREATE", "USER", saved.getId(), saved.getUsername() + " as " + saved.getRole());
        return UserView.from(saved);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize(Roles.ADMIN_UP)
    @Transactional
    public UserView updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUser request) {
        AppUser user = users.findById(id).filter(u -> u.getWorkspaceId().equals(current.workspaceId())).orElseThrow(() -> new NotFoundException("User", id));
        if ((request.role() == Role.OWNER || user.getRole() == Role.OWNER) && current.role() != Role.OWNER) throw new BadRequestException("Only an owner can change owners");
        user.update(request.displayName(), request.role(), request.enabled());
        audit.record(current.username(), "UPDATE", "USER", id, user.getUsername() + " as " + request.role() + (request.enabled() ? "" : " (disabled)"));
        return UserView.from(user);
    }

    private Workspace load() { return workspaces.findById(current.workspaceId()).orElseThrow(() -> new NotFoundException("Workspace", current.workspaceId())); }
}
