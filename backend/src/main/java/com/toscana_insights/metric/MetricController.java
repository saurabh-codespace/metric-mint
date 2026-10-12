package com.toscana_insights.metric;

import com.toscana_insights.query.MetricVerifier;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {
    private final MetricService service;
    private final MetricVerifier verifier;
    private final CurrentUser current;

    public MetricController(MetricService service, MetricVerifier verifier, CurrentUser current) { this.service = service; this.verifier = verifier; this.current = current; }

    @GetMapping @PreAuthorize(Roles.MEMBER)
    List<MetricDtos.Response> all() { return service.all(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ANALYST_UP)
    MetricDtos.Response create(@Valid @RequestBody MetricDtos.Request r) { return service.create(r, current.username()); }

    @PutMapping("/{id}") @PreAuthorize(Roles.ANALYST_UP)
    MetricDtos.Response update(@PathVariable UUID id, @Valid @RequestBody MetricDtos.Request r) { return service.update(id, r, current.username()); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ANALYST_UP)
    void delete(@PathVariable UUID id) { service.delete(id, current.username()); }

    @PostMapping("/{id}/approve") @PreAuthorize(Roles.ANALYST_UP)
    MetricDtos.Response approve(@PathVariable UUID id) { return service.approve(id, current.username()); }

    @PostMapping("/{id}/test") @PreAuthorize(Roles.ANALYST_UP)
    MetricDtos.TestResult test(@PathVariable UUID id) { return verifier.test(id); }

    @PostMapping("/{id}/verify") @PreAuthorize(Roles.ANALYST_UP)
    MetricDtos.TestResult verify(@PathVariable UUID id) { return verifier.verify(id, current.username()); }

    @GetMapping("/{id}/versions") @PreAuthorize(Roles.ANALYST_UP)
    List<MetricDtos.VersionView> versions(@PathVariable UUID id) { return service.history(id); }

    @GetMapping("/{id}/diff") @PreAuthorize(Roles.ANALYST_UP)
    List<MetricDtos.FieldChange> diff(@PathVariable UUID id, @RequestParam int from, @RequestParam int to) { return service.diff(id, from, to); }

    @PostMapping("/{id}/rollback") @PreAuthorize(Roles.ANALYST_UP)
    MetricDtos.Response rollback(@PathVariable UUID id, @RequestParam int version) { return service.rollback(id, version, current.username()); }
}
