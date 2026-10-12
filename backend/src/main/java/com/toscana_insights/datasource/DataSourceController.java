package com.toscana_insights.datasource;

import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Source management API. The admin UI and external callers use the same endpoints, so validation and audit rules are identical. */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {
    private final DataSourceService service;
    private final CurrentUser current;

    public DataSourceController(DataSourceService service, CurrentUser current) { this.service = service; this.current = current; }

    @GetMapping("/types") @PreAuthorize(Roles.MEMBER) List<String> types() { return DataSourceService.TYPES; }
    @GetMapping @PreAuthorize(Roles.ANALYST_UP) List<DataSourceDtos.Response> all() { return service.all(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ADMIN_UP)
    DataSourceDtos.Response create(@Valid @RequestBody DataSourceDtos.Request request) { return service.create(request, current.username()); }

    @PutMapping("/{id}") @PreAuthorize(Roles.ADMIN_UP)
    DataSourceDtos.Response update(@PathVariable UUID id, @Valid @RequestBody DataSourceDtos.Request request) { return service.update(id, request, current.username()); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ADMIN_UP)
    void delete(@PathVariable UUID id) { service.delete(id, current.username()); }

    @PostMapping("/{id}/test") @PreAuthorize(Roles.ADMIN_UP)
    DataSourceDtos.TestResult test(@PathVariable UUID id) { return service.test(id, current.username()); }

    @PostMapping("/{id}/pause") @PreAuthorize(Roles.ADMIN_UP)
    DataSourceDtos.Response pause(@PathVariable UUID id) { return service.pause(id, current.username()); }

    @PostMapping("/{id}/resume") @PreAuthorize(Roles.ADMIN_UP)
    DataSourceDtos.Response resume(@PathVariable UUID id) { return service.resume(id, current.username()); }

    @PostMapping("/{id}/rotate-secret") @PreAuthorize(Roles.ADMIN_UP)
    DataSourceDtos.Response rotate(@PathVariable UUID id, @Valid @RequestBody DataSourceDtos.RotateSecret request) { return service.rotateSecret(id, request.secret(), current.username()); }
}
