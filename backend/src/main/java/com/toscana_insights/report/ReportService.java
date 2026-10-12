package com.toscana_insights.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private final ReportRepository repository;
    private final AuditService audit;
    private final CurrentUser current;
    private final QueryService queries;
    private final ObjectMapper mapper;

    public ReportService(ReportRepository repository, AuditService audit, CurrentUser current, QueryService queries, ObjectMapper mapper) {
        this.repository = repository; this.audit = audit; this.current = current; this.queries = queries; this.mapper = mapper;
    }

    /** Own reports plus reports shared with the workspace. */
    @Transactional(readOnly = true)
    public List<ReportDtos.Response> all() {
        AppUser user = current.user();
        return repository.findByWorkspaceIdOrderByName(user.getWorkspaceId()).stream().filter(r -> r.isShared() || user.getUsername().equals(r.getOwnerName())).map(r -> view(r, user)).toList();
    }

    @Transactional
    public ReportDtos.Response create(ReportDtos.Request r, String actor) {
        AppUser user = current.user();
        Report report = new Report(user.getWorkspaceId(), actor);
        report.update(r.name(), r.visualization(), write(r.query()), r.favorite(), r.shared());
        Report saved = repository.save(report);
        audit.record(actor, "CREATE", "REPORT", saved.getId(), saved.getName() + (saved.isShared() ? " (shared)" : ""));
        return view(saved, user);
    }

    @Transactional
    public ReportDtos.Response update(UUID id, ReportDtos.Request r, String actor) {
        Report report = ownedOrAdmin(id);
        boolean sharingChanged = report.isShared() != r.shared();
        report.update(r.name(), r.visualization(), write(r.query()), r.favorite(), r.shared());
        audit.record(actor, sharingChanged ? "SHARE" : "UPDATE", "REPORT", id, report.getName() + (report.isShared() ? " (shared with workspace)" : " (private)"));
        return view(report, current.user());
    }

    @Transactional
    public ReportDtos.Response duplicate(UUID id, String actor) {
        Report source = visible(id);
        AppUser user = current.user();
        Report copy = new Report(user.getWorkspaceId(), actor);
        copy.update(source.getName() + " (copy)", source.getVisualization(), source.getConfiguration(), false, false);
        Report saved = repository.save(copy);
        audit.record(actor, "CREATE", "REPORT", saved.getId(), "duplicate of " + id);
        return view(saved, user);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        Report report = ownedOrAdmin(id);
        repository.delete(report);
        audit.record(actor, "DELETE", "REPORT", id, report.getName());
    }

    /** Runs the saved query as the current viewer, so row and column permissions are theirs, not the author's. */
    public QueryService.Result run(UUID id) {
        return queries.execute(spec(visible(id)));
    }

    public Report get(UUID id) { return visible(id); }

    // ---- helpers ----
    private Report visible(UUID id) {
        AppUser user = current.user();
        Report report = repository.findByIdAndWorkspaceId(id, user.getWorkspaceId()).orElseThrow(() -> new NotFoundException("Report", id));
        if (!report.isShared() && !user.getUsername().equals(report.getOwnerName())) throw new NotFoundException("Report", id);
        return report;
    }

    private Report ownedOrAdmin(UUID id) {
        Report report = visible(id);
        if (!current.username().equals(report.getOwnerName()) && !current.isAdmin()) throw new ForbiddenException("Only the owner or an admin can change this report");
        return report;
    }

    private ReportDtos.Response view(Report r, AppUser user) {
        QuerySpec spec;
        try { spec = spec(r); } catch (BadRequestException e) { spec = null; }
        return new ReportDtos.Response(r.getId(), r.getName(), r.getVisualization(), spec, r.isFavorite(), r.isShared(), r.getOwnerName(), user.getUsername().equals(r.getOwnerName()), r.getCreatedAt(), r.getUpdatedAt());
    }

    private QuerySpec spec(Report r) {
        try { return mapper.readValue(r.getConfiguration(), QuerySpec.class); }
        catch (JsonProcessingException e) { throw new BadRequestException("This report has a legacy configuration and cannot be run"); }
    }

    private String write(QuerySpec spec) {
        try {
            String json = mapper.writeValueAsString(spec);
            if (json.length() > 4000) throw new BadRequestException("Report definition is too large");
            return json;
        } catch (JsonProcessingException e) { throw new BadRequestException("Invalid report definition"); }
    }
}
