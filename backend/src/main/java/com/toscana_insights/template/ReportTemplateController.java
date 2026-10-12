package com.toscana_insights.template;

import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.toscana_insights.template.ReportTemplateDtos.*;

@RestController
@RequestMapping("/api/report-templates")
@PreAuthorize(Roles.MEMBER)
public class ReportTemplateController {
    private final ReportTemplateService service;
    private final TemplateGenerationService generation;
    public ReportTemplateController(ReportTemplateService service, TemplateGenerationService generation) { this.service = service; this.generation = generation; }

    @GetMapping("/ai/status") @PreAuthorize(Roles.ANALYST_UP)
    public Map<String, Boolean> aiStatus() { return Map.of("configured", generation.configured()); }

    @PostMapping("/generate") @PreAuthorize(Roles.ANALYST_UP)
    public TemplateGenerationService.Response generate(@Valid @RequestBody TemplateGenerationService.Request request) { return generation.generate(request); }

    @GetMapping public List<SavedTemplate> list() { return service.list(); }
    @GetMapping("/{id}") public SavedTemplate get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(Roles.ANALYST_UP)
    public SavedTemplate create(@RequestBody Definition definition) { return service.create(definition); }
    @PutMapping("/{id}") @PreAuthorize(Roles.ANALYST_UP)
    public SavedTemplate update(@PathVariable UUID id, @RequestBody Definition definition) { return service.update(id, definition); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(Roles.ANALYST_UP)
    public void delete(@PathVariable UUID id) { service.delete(id); }
    @PostMapping("/preview") public RunResult preview(@RequestBody Definition definition) { return service.runDefinition(definition); }
    @GetMapping("/{id}/run") public RunResult run(@PathVariable UUID id) { return service.runDefinition(service.get(id).definition()); }
    @GetMapping("/sources") public List<SourceOption> sources() { return service.sources(); }
    @GetMapping("/sources/{type}/{id}") public SourceData source(@PathVariable String type, @PathVariable String id) {
        return service.source(type, id);
    }
}
