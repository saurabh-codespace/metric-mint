package com.toscana_insights.dashboard;

import com.toscana_insights.audit.AuditEventRepository;
import com.toscana_insights.conversation.ConversationRepository;
import com.toscana_insights.datasource.DataSourceRepository;
import com.toscana_insights.metric.SemanticMetricRepository;
import com.toscana_insights.report.ReportRepository;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DataSourceRepository dataSources;
    private final SemanticMetricRepository metrics;
    private final ConversationRepository conversations;
    private final ReportRepository reports;
    private final AuditEventRepository audits;
    private final CurrentUser current;
    private final JdbcTemplate jdbc;

    public DashboardController(DataSourceRepository dataSources, SemanticMetricRepository metrics, ConversationRepository conversations, ReportRepository reports,
                               AuditEventRepository audits, CurrentUser current, JdbcTemplate jdbc) {
        this.dataSources = dataSources; this.metrics = metrics; this.conversations = conversations; this.reports = reports; this.audits = audits; this.current = current; this.jdbc = jdbc;
    }

    @GetMapping("/summary") @PreAuthorize(Roles.MEMBER)
    Summary summary() {
        UUID ws = current.workspaceId();
        return new Summary(dataSources.countByWorkspaceId(ws), metrics.countByWorkspaceIdAndActiveTrue(ws), conversations.countByWorkspaceId(ws), reports.countByWorkspaceId(ws),
                audits.countByWorkspaceId(ws), Instant.now());
    }

    public record Summary(long dataSources, long activeMetrics, long conversations, long reports, long auditEvents, Instant generatedAt) {}
    @GetMapping("/sample-invoices") @PreAuthorize(Roles.MEMBER)
    public List<Invoice> invoices() {
        return jdbc.query("""
            SELECT id, vendor, business_unit, invoice_date, amount, currency, status,
                   cycle_days, automated, sample_as_of,
                   DATEDIFF(day, invoice_date, sample_as_of) AS age
            FROM toscana_insights.dashboard_sample_invoices
            ORDER BY invoice_date DESC, id DESC
            """, (rs, row) -> new Invoice(rs.getString("id"), rs.getString("vendor"),
                rs.getString("business_unit"), rs.getObject("invoice_date", LocalDate.class),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"),
                rs.getDouble("cycle_days"), rs.getBoolean("automated"), rs.getInt("age"),
                rs.getObject("sample_as_of", LocalDate.class)));
    }

    public record Invoice(String id, String vendor, String unit, LocalDate date, BigDecimal amount,
                          String currency, String status, double cycle, boolean automated, int age, LocalDate asOf) {}
}
