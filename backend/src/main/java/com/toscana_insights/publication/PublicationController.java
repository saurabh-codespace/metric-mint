package com.toscana_insights.publication;

import com.toscana_insights.workspace.Roles;
import com.toscana_insights.template.ReportTemplateDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/published-dashboards")
public class PublicationController {
    private final PublicationService service;

    public PublicationController(PublicationService service) { this.service = service; }

    @GetMapping @PreAuthorize(Roles.MEMBER)
    List<PublicationDtos.Response> all() { return service.all(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ANALYST_UP)
    PublicationDtos.Response publish(@Valid @RequestBody PublicationDtos.Request request) { return service.publish(request); }

    @GetMapping("/token/{token}") @PreAuthorize(Roles.MEMBER)
    PublicationDtos.Response byToken(@PathVariable String token) { return service.byToken(token); }

    @GetMapping("/token/{token}/data") @PreAuthorize(Roles.MEMBER)
    ReportTemplateDtos.RunResult data(@PathVariable String token) { return service.data(token); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ANALYST_UP)
    void revoke(@PathVariable UUID id) { service.revoke(id); }
}
