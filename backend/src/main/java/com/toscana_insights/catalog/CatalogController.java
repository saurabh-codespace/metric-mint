package com.toscana_insights.catalog;

import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    public record RegisterTable(@NotBlank String table) {}
    public record CreateRelationship(@NotNull UUID fromTableId, @NotBlank String fromColumn, @NotNull UUID toTableId, @NotBlank String toColumn, @NotBlank String cardinality) {}
    public record CreatePolicy(@NotNull UUID tableId, @NotBlank String columnName, @NotBlank String matchValue, String role, String username) {}

    private final CatalogService service;
    private final CurrentUser current;

    public CatalogController(CatalogService service, CurrentUser current) { this.service = service; this.current = current; }

    @GetMapping("/tables") @PreAuthorize(Roles.MEMBER)
    List<CatalogService.TableView> tables() { return service.list(); }

    @PutMapping("/tables/{id}") @PreAuthorize(Roles.ANALYST_UP)
    CatalogService.TableView update(@PathVariable UUID id, @RequestBody CatalogService.UpdateTable request) { return service.update(id, request, current.username()); }

    @PostMapping("/sources/{sourceId}/tables") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ADMIN_UP)
    CatalogService.TableView register(@PathVariable UUID sourceId, @Valid @RequestBody RegisterTable request) { return service.registerRemote(sourceId, request.table(), current.username()); }

    @GetMapping("/relationships") @PreAuthorize(Roles.ANALYST_UP)
    List<CatalogService.RelationshipView> relationships() { return service.relationships(); }

    @PostMapping("/relationships") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ANALYST_UP)
    CatalogService.RelationshipView createRelationship(@Valid @RequestBody CreateRelationship r) {
        return service.createRelationship(r.fromTableId(), r.fromColumn(), r.toTableId(), r.toColumn(), r.cardinality(), current.username());
    }

    @PostMapping("/relationships/{id}/test") @PreAuthorize(Roles.ANALYST_UP)
    CatalogService.RelationshipView testRelationship(@PathVariable UUID id) { return service.testRelationship(id, current.username()); }

    @DeleteMapping("/relationships/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ANALYST_UP)
    void deleteRelationship(@PathVariable UUID id) { service.deleteRelationship(id, current.username()); }

    @GetMapping("/policies") @PreAuthorize(Roles.ADMIN_UP)
    List<CatalogService.PolicyView> policies() { return service.policies(); }

    @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ADMIN_UP)
    CatalogService.PolicyView createPolicy(@Valid @RequestBody CreatePolicy r) {
        return service.createPolicy(r.tableId(), r.columnName(), r.matchValue(), r.role(), r.username(), current.username());
    }

    @DeleteMapping("/policies/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ADMIN_UP)
    void deletePolicy(@PathVariable UUID id) { service.deletePolicy(id, current.username()); }
}
