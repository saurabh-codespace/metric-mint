package com.toscana_insights.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.template.ReportTemplateDtos;
import com.toscana_insights.template.ReportTemplateService;
import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Role;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> REPORTS = Set.of("queue-management", "on-hold", "csat-dashboard", "savings-collection", "utilization");
    private static final Set<String> UNITS = Set.of("all", "Technology", "Operations", "Finance", "Marketing", "People");
    private static final Set<String> WORKBOOK_CHARTS = Set.of("bar", "column", "line", "area", "pie", "donut", "table");
    private final PublishedDashboardRepository repository;
    private final CurrentUser current;
    private final AuditService audit;
    private final ReportTemplateService templates;
    private final ObjectMapper json;

    public PublicationService(PublishedDashboardRepository repository, CurrentUser current, AuditService audit,
                              ReportTemplateService templates, ObjectMapper json) {
        this.repository = repository; this.current = current; this.audit = audit;
        this.templates = templates; this.json = json;
    }

    @Transactional(readOnly = true)
    public List<PublicationDtos.Response> all() {
        AppUser user = current.user();
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(user.getWorkspaceId()).stream().map(d -> view(d, user)).toList();
    }

    @Transactional
    public PublicationDtos.Response publish(PublicationDtos.Request request) {
        AppUser user = current.user();
        PublicationDtos.Request definition = validate(request);
        String snapshot = null;
        if ("template".equals(definition.kind())) {
            ReportTemplateDtos.Definition template = templates.get(UUID.fromString(definition.reportId())).definition();
            templates.validateDefinition(template);
            try { snapshot = json.writeValueAsString(template); }
            catch (JsonProcessingException e) { throw new BadRequestException("Cannot serialize the template definition"); }
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PublishedDashboard saved = repository.save(new PublishedDashboard(user.getWorkspaceId(), user.getUsername(), definition, token, snapshot));
        audit.record(user.getWorkspaceId(), user.getUsername(), "PUBLISH", "DASHBOARD", saved.getId(), saved.getName());
        return view(saved, user);
    }

    @Transactional(readOnly = true)
    public PublicationDtos.Response byToken(String token) {
        AppUser user = current.user();
        return view(findByToken(token, user), user);
    }

    @Transactional(readOnly = true)
    public ReportTemplateDtos.RunResult data(String token) {
        PublishedDashboard dashboard = findByToken(token, current.user());
        if (!"template".equals(dashboard.getKind()) || dashboard.getTemplateConfiguration() == null) {
            throw new BadRequestException("This publication does not contain a report template");
        }
        try {
            ReportTemplateDtos.Definition definition = json.readValue(dashboard.getTemplateConfiguration(), ReportTemplateDtos.Definition.class);
            // Run the frozen layout with the viewer's current source permissions, never the publisher's data.
            return templates.runDefinition(definition);
        } catch (JsonProcessingException e) { throw new BadRequestException("The published template definition is invalid"); }
    }

    private PublishedDashboard findByToken(String token, AppUser user) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw missing();
        return repository.findByTokenAndWorkspaceId(token, user.getWorkspaceId())
                .filter(d -> token.equals(d.getToken())).orElseThrow(PublicationService::missing);
    }

    @Transactional
    public void revoke(UUID id) {
        AppUser user = current.user();
        PublishedDashboard dashboard = repository.findByIdAndWorkspaceId(id, user.getWorkspaceId()).orElseThrow(PublicationService::missing);
        if (!canRevoke(dashboard, user)) throw new ForbiddenException("Only the publisher or an admin can revoke this link");
        repository.delete(dashboard);
        audit.record(user.getWorkspaceId(), user.getUsername(), "REVOKE", "DASHBOARD", id, dashboard.getName());
    }

    private static PublicationDtos.Request validate(PublicationDtos.Request r) {
        boolean workbook = "workbook".equals(r.kind());
        boolean template = "template".equals(r.kind());
        if (!workbook && !template && !"executive".equals(r.kind())) throw new BadRequestException("Unknown dashboard kind");
        if (workbook && (r.reportId() == null || !REPORTS.contains(r.reportId()))) throw new BadRequestException("Unknown workbook report");
        if (template) {
            try {
                if (r.reportId() == null || !UUID.fromString(r.reportId()).toString().equalsIgnoreCase(r.reportId())) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) { throw new BadRequestException("A valid template report ID is required"); }
        }
        if (!workbook && !template && r.reportId() != null) throw new BadRequestException("Executive dashboards do not have a workbook report");
        int days = r.days() == null ? 30 : r.days();
        if (!Set.of(7, 30, 90).contains(days)) throw new BadRequestException("Days must be 7, 30 or 90");
        String unit = r.unit() == null ? "all" : r.unit();
        if (!UNITS.contains(unit)) throw new BadRequestException("Unknown business unit");
        String chart = r.chart() == null ? (template ? "table" : workbook ? "bar" : "volume") : r.chart();
        if (!(workbook || template ? WORKBOOK_CHARTS : Set.of("volume", "spend")).contains(chart)) throw new BadRequestException("Unsupported chart for this dashboard");
        return new PublicationDtos.Request(r.name().strip(), r.kind(), r.reportId(), days, unit, chart);
    }

    private static boolean canRevoke(PublishedDashboard d, AppUser user) {
        return user.getRole() == Role.OWNER || user.getRole() == Role.ADMIN
                || (user.getRole() == Role.ANALYST && user.getUsername().equals(d.getOwnerName()));
    }

    private static PublicationDtos.Response view(PublishedDashboard d, AppUser user) {
        return new PublicationDtos.Response(d.getId(), d.getName(), d.getKind(), d.getReportId(), d.getDays(), d.getUnit(),
                d.getChart(), d.getToken(), d.getCreatedAt(), d.getOwnerName(), canRevoke(d, user));
    }

    private static NotFoundException missing() { return new NotFoundException("Published dashboard", "unavailable"); }
}
