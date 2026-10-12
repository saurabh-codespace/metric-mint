package com.toscana_insights.conversation;

import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@PreAuthorize(Roles.MEMBER)
public class ProjectController {
    private final ProjectRepository projects;
    private final CurrentUser current;

    public ProjectController(ProjectRepository projects, CurrentUser current) { this.projects = projects; this.current = current; }

    @GetMapping
    List<Response> list() {
        var user = current.user();
        return projects.findByWorkspaceIdAndOwnerNameOrderByCreatedAtDesc(user.getWorkspaceId(), user.getUsername()).stream().map(ProjectController::view).toList();
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Response create(@Valid @RequestBody Request request) {
        var user = current.user();
        return view(projects.save(new Project(user.getWorkspaceId(), user.getUsername(), request.name().strip())));
    }

    private static Response view(Project project) { return new Response(project.getId(), project.getName(), project.getCreatedAt()); }
    public record Request(@NotBlank @Size(max = 80) String name) {}
    public record Response(UUID id, String name, Instant createdAt) {}
}
