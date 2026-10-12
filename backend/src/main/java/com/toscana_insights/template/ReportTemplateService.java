package com.toscana_insights.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.report.ReportService;
import com.toscana_insights.workbook.WorkbookReportService;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Role;
import com.toscana_insights.workspace.WorkspaceRepository;
import com.toscana_insights.toscana.ToscanaAnalyticsService;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.toscana_insights.template.ReportTemplateDtos.*;

@Service
public class ReportTemplateService {
    private final ReportTemplateRepository repository;
    private final CurrentUser current;
    private final WorkbookReportService workbooks;
    private final ReportService reports;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final AuditService audit;
    private final ToscanaAnalyticsService connected;
    private final WorkspaceRepository workspaces;
    private static final Set<String> CONNECTED_REPORTS = Set.of("invoice-intake", "invoice-status", "on-hold", "high-aging-sla");
    private static final List<SourceOption> CONNECTED_OPTIONS = List.of(
            new SourceOption("connected", "invoice-intake", "AP LOB 90: Invoice Intake Trend", false),
            new SourceOption("connected", "invoice-status", "AP LOB 90: Invoice Status Distribution", false),
            new SourceOption("connected", "on-hold", "AP LOB 90: On Hold Report", false),
            new SourceOption("connected", "high-aging-sla", "AP LOB 90: High Aging SLA Case report", false));

    public ReportTemplateService(ReportTemplateRepository repository, CurrentUser current,
            WorkbookReportService workbooks, ReportService reports, ObjectMapper mapper, Validator validator, AuditService audit,
            ToscanaAnalyticsService connected, WorkspaceRepository workspaces) {
        this.repository = repository; this.current = current; this.workbooks = workbooks;
        this.reports = reports; this.mapper = mapper; this.validator = validator; this.audit = audit;
        this.connected = connected; this.workspaces = workspaces;
    }

    @Transactional(readOnly = true)
    public List<SavedTemplate> list() {
        return repository.findByWorkspaceIdOrderByName(current.workspaceId()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SavedTemplate get(UUID id) { return view(visible(id)); }

    @Transactional
    public SavedTemplate create(Definition definition) {
        requireAuthor();
        validateDefinition(definition);
        ReportTemplate template = new ReportTemplate(current.workspaceId(), current.username());
        template.update(definition.name(), write(definition));
        repository.saveAndFlush(template);
        audit.record(current.username(), "CREATE", "REPORT_TEMPLATE", template.getId(), template.getName());
        return view(template);
    }

    @Transactional
    public SavedTemplate update(UUID id, Definition definition) {
        ReportTemplate template = editable(id);
        validateDefinition(definition);
        template.update(definition.name(), write(definition));
        repository.saveAndFlush(template);
        audit.record(current.username(), "UPDATE", "REPORT_TEMPLATE", id, template.getName());
        return view(template);
    }

    @Transactional
    public void delete(UUID id) {
        ReportTemplate template = editable(id);
        repository.delete(template);
        audit.record(current.username(), "DELETE", "REPORT_TEMPLATE", id, template.getName());
    }

    public List<SourceOption> sources() {
        List<SourceOption> result = new ArrayList<>();
        workbooks.list().forEach(r -> result.add(new SourceOption("workbook", r.id(), r.title(), true)));
        reports.all().forEach(r -> result.add(new SourceOption("report", r.id().toString(), r.name(), false)));
        if (connectedAllowed()) result.addAll(CONNECTED_OPTIONS);
        return result;
    }

    public SourceData source(String type, String id) {
        if ("connected".equals(type)) {
            if (!connectedAllowed()) throw new ForbiddenException("Connected operational reports require a default-workspace administrator");
            Map<String, Object> report = connectedReports().stream().filter(r -> id.equals(r.get("id"))).findFirst()
                    .orElseThrow(() -> new NotFoundException("Connected report", id));
            List<Map<String, Object>> rows = mapper.convertValue(report.get("data"), new TypeReference<>() {});
            List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet());
            return new SourceData(columns, rows, false, List.of("Live operational AP LOB 90 data; no custom date or field filters. " + report.get("description")),
                    mapper.convertValue(report.get("generatedAt"), Instant.class));
        }
        if ("workbook".equals(type)) {
            var report = workbooks.get(id);
            List<String> columns = report.data().isEmpty() ? List.of() : List.copyOf(report.data().getFirst().keySet());
            return new SourceData(columns, report.data(), true,
                    List.of("Synthetic sample data. Snapshot: " + report.sampleAsOf() + "."), Instant.now());
        }
        if ("report".equals(type)) {
            UUID reportId;
            try { reportId = UUID.fromString(id); }
            catch (IllegalArgumentException e) { throw new BadRequestException("Invalid saved report id"); }
            // ReportService executes with the current viewer's row and column permissions.
            var result = reports.run(reportId);
            List<String> warnings = new ArrayList<>(result.warnings());
            if (result.truncated()) warnings.add("Source rows were truncated by the query limit.");
            return new SourceData(result.columns(), result.rows(), false, warnings, result.executedAt());
        }
        throw new BadRequestException("Unknown template source type");
    }

    private boolean connectedAllowed() {
        return current.isAdmin() && workspaces.findById(current.workspaceId()).map(w -> "default".equals(w.getSlug())).orElse(false);
    }

    private List<Map<String, Object>> connectedReports() {
        return connected.reports().stream().filter(r -> "READY".equals(r.get("status")) && CONNECTED_REPORTS.contains(r.get("id"))).toList();
    }

    public void validateDefinition(Definition definition) { runDefinition(definition); }

    public RunResult runDefinition(Definition definition) {
        if (definition == null || !validator.validate(definition).isEmpty()) {
            throw new BadRequestException("Invalid template: version 1, a name and 1–12 valid widgets are required");
        }
        write(definition);
        Set<String> ids = new HashSet<>();
        for (Widget widget : definition.widgets()) {
            if (!ids.add(widget.id())) throw new BadRequestException("Widget ids must be unique");
        }
        Map<String, SourceData> cache = new HashMap<>();
        List<WidgetResult> widgets = new ArrayList<>();
        for (Widget widget : definition.widgets()) {
            SourceData data = cache.computeIfAbsent(widget.sourceType() + ":" + widget.sourceId(),
                    key -> source(widget.sourceType(), widget.sourceId()));
            validateFields(widget, data);
            widgets.add(new WidgetResult(widget, data.columns(), data.rows(), data.sample(), data.warnings(), data.executedAt()));
        }
        return new RunResult(definition, widgets);
    }

    private void validateFields(Widget widget, SourceData data) {
        if (!data.columns().containsAll(widget.columns()) || new HashSet<>(widget.columns()).size() != widget.columns().size()) {
            throw new BadRequestException("Widget columns must be distinct fields from its source");
        }
        if ("table".equals(widget.chart())) {
            if (widget.columns().isEmpty()) throw new BadRequestException("A table needs at least one column");
            return;
        }
        if (widget.labelKey() == null || widget.valueKey() == null
                || !data.columns().contains(widget.labelKey()) || !data.columns().contains(widget.valueKey())) {
            throw new BadRequestException("Charts need label and value fields from their source");
        }
        boolean numeric = false;
        for (Map<String, Object> row : data.rows()) {
            Object value = row.get(widget.valueKey());
            if (value == null) continue;
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new BadRequestException("Chart values must be numeric");
            }
            numeric = true;
            if (("pie".equals(widget.chart()) || "donut".equals(widget.chart())) && number.doubleValue() < 0) {
                throw new BadRequestException("Pie and donut values cannot be negative");
            }
        }
        if (!data.rows().isEmpty() && !numeric) throw new BadRequestException("Chart value field has no numeric values");
    }

    private ReportTemplate visible(UUID id) {
        return repository.findByIdAndWorkspaceId(id, current.workspaceId())
                .orElseThrow(() -> new NotFoundException("Report template", id));
    }

    private ReportTemplate editable(UUID id) {
        requireAuthor();
        ReportTemplate template = visible(id);
        if (!canEdit(template)) throw new ForbiddenException("Only the template owner or an admin can change it");
        return template;
    }

    private void requireAuthor() {
        if (current.role() != Role.ANALYST && !current.isAdmin()) throw new ForbiddenException("An analyst role is required");
    }

    private boolean canEdit(ReportTemplate template) {
        return current.isAdmin() || (current.role() == Role.ANALYST && current.username().equals(template.getOwnerName()));
    }

    private SavedTemplate view(ReportTemplate template) {
        try {
            return new SavedTemplate(template.getId(), mapper.readValue(template.getDefinition(), Definition.class),
                    template.getOwnerName(), canEdit(template), template.getCreatedAt(), template.getUpdatedAt());
        } catch (JsonProcessingException e) { throw new BadRequestException("The stored template definition is invalid"); }
    }

    private String write(Definition definition) {
        try {
            String json = mapper.writeValueAsString(definition);
            if (json.getBytes(StandardCharsets.UTF_8).length > 65536) throw new BadRequestException("Template JSON exceeds 64 KB");
            return json;
        } catch (JsonProcessingException e) { throw new BadRequestException("Invalid template definition"); }
    }
}
