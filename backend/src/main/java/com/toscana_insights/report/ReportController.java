package com.toscana_insights.report;

import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;
    private final ReportExporter exporter;
    private final AuditService audit;
    private final CurrentUser current;

    public ReportController(ReportService service, ReportExporter exporter, AuditService audit, CurrentUser current) {
        this.service = service; this.exporter = exporter; this.audit = audit; this.current = current;
    }

    @GetMapping @PreAuthorize(Roles.MEMBER)
    List<ReportDtos.Response> all() { return service.all(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ANALYST_UP)
    ReportDtos.Response create(@Valid @RequestBody ReportDtos.Request r) { return service.create(r, current.username()); }

    @PutMapping("/{id}") @PreAuthorize(Roles.ANALYST_UP)
    ReportDtos.Response update(@PathVariable UUID id, @Valid @RequestBody ReportDtos.Request r) { return service.update(id, r, current.username()); }

    @PostMapping("/{id}/duplicate") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ANALYST_UP)
    ReportDtos.Response duplicate(@PathVariable UUID id) { return service.duplicate(id, current.username()); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ANALYST_UP)
    void delete(@PathVariable UUID id) { service.delete(id, current.username()); }

    @GetMapping("/{id}/run") @PreAuthorize(Roles.MEMBER)
    QueryService.Result run(@PathVariable UUID id) { return service.run(id); }

    /** Exports re-run the query as the caller, so a file never contains more than the caller may see. */
    @GetMapping("/{id}/export") @PreAuthorize(Roles.MEMBER)
    ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "csv") String format) {
        String name = service.get(id).getName();
        QueryService.Result result = service.run(id);
        byte[] body;
        MediaType type;
        switch (format.toLowerCase()) {
            case "csv" -> { body = exporter.csv(result); type = MediaType.parseMediaType("text/csv"); }
            case "pdf" -> { body = exporter.pdf(name, result); type = MediaType.APPLICATION_PDF; }
            default -> throw new BadRequestException("format must be csv or pdf");
        }
        audit.record(current.username(), "EXPORT", "REPORT", id, format.toLowerCase() + ", " + result.rows().size() + " rows");
        String file = name.replaceAll("[^A-Za-z0-9._-]+", "_") + "." + format.toLowerCase();
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString()).body(body);
    }
}
