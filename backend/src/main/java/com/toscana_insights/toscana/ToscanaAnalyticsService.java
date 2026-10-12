package com.toscana_insights.toscana;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToscanaAnalyticsService {
    static final String OVERVIEW_CACHE = "toscanaOverview";
    static final String SCHEMA_CACHE = "toscanaSchema";
    static final String REPORTS_CACHE = "toscanaReports";
    private static final String DATABASE = "Toscana_I2P-SIT";
    private final JdbcTemplate jdbc;

    public ToscanaAnalyticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Cacheable(OVERVIEW_CACHE)
    public Overview overview() {
        List<LobSummary> lobs = jdbc.query(lobSql(), (rs, row) -> new LobSummary(rs.getString("name"), rs.getLong("total"), rs.getLong("completed"), rs.getLong("total") - rs.getLong("completed"), rs.getDouble("avg_ageing")));
        long total = lobs.stream().mapToLong(LobSummary::total).sum();
        long completed = lobs.stream().mapToLong(LobSummary::completed).sum();
        double rate = total == 0 ? 0 : completed * 100.0 / total;
        return new Overview(DATABASE, Instant.now(), total, completed, total - completed, lobs.size(), rate, lobs, categoryCounts(statusSql()), periods(), categoryCounts(regionSql()));
    }

    @Cacheable(SCHEMA_CACHE)
    public List<TableInfo> schema() {
        return jdbc.query(schemaSql(), (rs, row) -> new TableInfo(rs.getString("schema_name"), rs.getString("table_name"), rs.getLong("row_count"), rs.getString("category")));
    }

    @Cacheable(REPORTS_CACHE)
    public List<Map<String, Object>> reports() {
        Overview overview = overview();
        List<CategoryCount> onHold = categoryCounts(onHoldSql());
        List<AgingSummary> aging = jdbc.query(agingSql(), (rs, row) -> new AgingSummary(rs.getString("agingBand"), rs.getString("status"), rs.getLong("count_value")));
        List<SupplierSpend> spend = jdbc.query(supplierSpendSql(), (rs, row) -> new SupplierSpend(rs.getString("vendorName"), rs.getString("currency"), rs.getLong("purchaseOrders"), rs.getBigDecimal("totalSpend")));
        Instant generatedAt = Instant.now();
        return List.of(
                report("queue-management", "Queue Management Dashboard", "Safe regional AP workload because no AP queue table is available.", "Operations", "stacked-bar", "READY", "SQL_SERVER", sum(overview.queues()), generatedAt, overview.queues()),
                report("on-hold", "On Hold Report", "AP work items grouped by governed on-hold status.", "Operations", "donut", "READY", "SQL_SERVER", sum(onHold), generatedAt, onHold),
                report("high-aging-sla", "High Aging SLA Case report", "Open AP workload grouped into aging bands and governed statuses.", "SLA", "area", "READY", "SQL_SERVER", aging.stream().mapToLong(AgingSummary::count).sum(), generatedAt, aging),
                report("mro-po-detail", "MRO - Purchase Order Detail", "SAP purchasing tables do not contain an MRO classification field.", "Purchasing", "table", "SOURCE_REQUIRED", "SAP_API", 0, generatedAt, List.of()),
                report("supplier-spend", "Supplier Spend Overview", "Top suppliers by aggregated SAP purchase-order spend; no vendor identifiers or tax fields are returned.", "Spend", "bar", "READY", "SAP_API", spend.stream().mapToLong(SupplierSpend::purchaseOrders).sum(), generatedAt, spend),
                report("wbr-governance", "WBR Governance report", "Weekly governance KPIs for AP completion, open workload and regional allocation.", "Governance", "kpi", "READY", "SQL_SERVER", overview.totalWorkItems(), generatedAt, governanceKpis(overview)),
                report("mbr-governance", "MBR Governance report", "Monthly AP intake trend for management review.", "Governance", "line", "READY", "SQL_SERVER", overview.monthlyVolume().stream().mapToLong(PeriodCount::count).sum(), generatedAt, overview.monthlyVolume()),
                report("invoice-intake", "Invoice Intake Trend", "AP LOB 90 counts by creation month; latest 12 available months, not a custom date range.", "Trend", "area", "READY", "SQL_SERVER", overview.monthlyVolume().stream().mapToLong(PeriodCount::count).sum(), generatedAt, overview.monthlyVolume()),
                report("invoice-status", "Invoice Status Distribution", "AP LOB 90 status counts; up to 12 most common statuses across available history.", "Operations", "bar", "READY", "SQL_SERVER", sum(overview.statuses()), generatedAt, overview.statuses()),
                report("change-of-hands", "Change of Hands process", "Assignment movement history is not available in the current I2P tables.", "SLA", "line", "SOURCE_REQUIRED", "ICERTIS_API", 0, generatedAt, List.of()),
                report("gmb-mailbox", "GMB Mailbox tracking", "Connect an approved SharePoint or mailbox feed for volume and response analysis.", "Service", "bar", "SOURCE_REQUIRED", "SHAREPOINT", 0, generatedAt, List.of()),
                report("csat-dashboard", "CSAT Dashboard", "Connect Salesforce, a survey API, or a governed dump for satisfaction trends.", "Quality", "line", "SOURCE_REQUIRED", "SALESFORCE_API", 0, generatedAt, List.of()),
                report("hiring-dashboard", "Hiring Dashboard Maintenance", "Connect the approved HR API, SharePoint list, or governed dump.", "Workforce", "table", "SOURCE_REQUIRED", "REST_API", 0, generatedAt, List.of()));
    }

    @CacheEvict(cacheNames = {OVERVIEW_CACHE, SCHEMA_CACHE, REPORTS_CACHE}, allEntries = true, beforeInvocation = true)
    public void refreshCaches() {}

    public Answer answer(String question) {
        String intent = question.toLowerCase(Locale.ROOT).strip().replaceAll("[?!.]+$", "").strip().replaceAll("\\s+", " ")
                .replaceFirst("^(?:please )?(?:show(?: me)?|give me|what is|what are|summarize) (?:the )?", "");
        String scope = "(?:(?:ap|accounts payable|i2p) )?";
        String items = "(?:invoices?|work items?)";
        if (intent.matches("monthly " + scope + items + "(?: volume| intake| trend)?")
                || intent.matches(scope + "monthly (?:volume|intake|trend)")
                || intent.matches(scope + items + "(?: volume| intake)? by month")) {
            List<PeriodCount> rows = periods();
            long total = rows.stream().mapToLong(PeriodCount::count).sum();
            return new Answer("Accounts Payable (LOB 90) has " + total + " work items across the displayed creation months.", rows,
                    "Source: AccountsPayable_External, LOB 90 only. Latest 12 available creation months; no date or regional filter applied.");
        }
        if (intent.matches(scope + "(?:" + items + " )?status(?: distribution| summary| breakdown)?")) {
            return new Answer("Here is the Accounts Payable (LOB 90) status distribution.", categoryCounts(statusSql()),
                    "Source: AccountsPayable_External joined to TOSCANA3_STATUSMASTER for LOB 90 only. Up to 12 most common statuses; no additional filters applied.");
        }
        if (intent.matches(scope + "(?:regional workload|workload by region|" + items + " by region|regional " + items + ")")) {
            List<CategoryCount> rows = categoryCounts(regionSql());
            String answer = rows.isEmpty() ? "No Accounts Payable (LOB 90) regional data was returned."
                    : "The largest regional Accounts Payable (LOB 90) workload is " + rows.getFirst().name() + " with " + rows.getFirst().count() + " items.";
            return new Answer(answer, rows,
                    "Source: AccountsPayable_External, LOB 90 only, grouped by Region. Up to 12 regions across all statuses; this is not queue data.");
        }
        boolean lobComparison = intent.matches("which lob has (?:the )?most open invoices");
        if (lobComparison || intent.matches(scope + "open (?:" + items + "|workload)(?: summary)?")
                || intent.matches("open " + scope + items + "(?: summary)?")
                || intent.matches("how many open " + scope + items + "(?: are there)?")) {
            List<LobSummary> rows = jdbc.query(lobSql(), (rs, row) -> new LobSummary("Accounts Payable (LOB 90)", rs.getLong("total"),
                    rs.getLong("completed"), rs.getLong("total") - rs.getLong("completed"), rs.getDouble("avg_ageing")));
            long open = rows.stream().mapToLong(LobSummary::open).sum();
            return new Answer("Accounts Payable (LOB 90) has " + open + " open work items." + (lobComparison ? " This source covers one LOB and cannot rank all I2P LOBs." : ""), rows,
                    "Source: AccountsPayable_External, LOB 90 only. Open means Completed_Date is null; no additional filters applied.");
        }
        return Answer.clarify("I cannot answer that request with the supported Accounts Payable (LOB 90) summaries. "
                + "Try 'Show monthly invoice volume', 'Show invoice status distribution', or 'Show open AP invoices'. "
                + "For invoice detail, try 'Show the latest invoice in the duplicate queue'. Regional totals are not queue data; no summary query was run.");
    }

    private List<CategoryCount> categoryCounts(String sql) { return jdbc.query(sql, (rs, row) -> new CategoryCount(rs.getString("name"), rs.getLong("count_value"))); }
    private List<PeriodCount> periods() { return jdbc.query(monthlySql(), (rs, row) -> new PeriodCount(rs.getString("period"), rs.getLong("count_value"))); }
    private long sum(List<CategoryCount> values) { return values.stream().mapToLong(CategoryCount::count).sum(); }
    private Map<String, Object> governanceKpis(Overview overview) { return Map.of("totalWorkItems", overview.totalWorkItems(), "openWorkItems", overview.openWorkItems(), "completedWorkItems", overview.completedWorkItems(), "completionRate", overview.completionRate()); }

    private Map<String, Object> report(String id, String title, String description, String category, String chartType, String status, String sourceType, long recordCount, Instant generatedAt, Object data) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", id); report.put("title", title); report.put("description", description); report.put("category", category);
        report.put("chartType", chartType); report.put("status", status); report.put("sourceType", sourceType);
        report.put("recordCount", recordCount); report.put("generatedAt", generatedAt); report.put("data", data);
        return report;
    }

    private String schemaSql() { return """
            SELECT 'dbo' schema_name,'AccountsPayable_External' table_name,COUNT_BIG(*) row_count,'I2P' category
            FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External WHERE lobId=90
            UNION ALL SELECT 'sap','EKKO',COUNT_BIG(*),'SAP' FROM [Toscana_I2P-SIT].sap.EKKO
            UNION ALL SELECT 'sap','EKPO',COUNT_BIG(*),'SAP' FROM [Toscana_I2P-SIT].sap.EKPO
            UNION ALL SELECT 'sap','MSEG',COUNT_BIG(*),'SAP' FROM [Toscana_I2P-SIT].sap.MSEG
            UNION ALL SELECT 'sap','MKPF',COUNT_BIG(*),'SAP' FROM [Toscana_I2P-SIT].sap.MKPF
            UNION ALL SELECT 'sap','LFA1',COUNT_BIG(*),'SAP' FROM [Toscana_I2P-SIT].sap.LFA1
            UNION ALL SELECT 'sap','T001',COUNT_BIG(*),'SAP' FROM [Toscana_I2P-SIT].sap.T001
            ORDER BY row_count DESC
            """; }

    private String lobSql() { return """
            SELECT 'Accounts Payable / I2P' name,COUNT_BIG(*) total,
                   COALESCE(SUM(CASE WHEN Completed_Date IS NOT NULL THEN 1 ELSE 0 END),0) completed,
                   COALESCE(AVG(CAST(Ageing AS float)),0) avg_ageing
            FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External WHERE lobId=90
            """; }

    private String statusSql() { return """
            SELECT TOP 12 COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'Unknown') name,COUNT_BIG(*) count_value
            FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External e
            LEFT JOIN [Toscana_I2P-SIT].dbo.TOSCANA3_STATUSMASTER sm ON sm.StatusID=e.StatusID AND sm.LobID=e.lobId
            WHERE e.lobId=90
            GROUP BY COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'Unknown') ORDER BY count_value DESC
            """; }

    private String regionSql() { return """
            SELECT TOP 12 COALESCE(NULLIF(LTRIM(RTRIM(Region)),''),'Unassigned') name,COUNT_BIG(*) count_value
            FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External WHERE lobId=90
            GROUP BY COALESCE(NULLIF(LTRIM(RTRIM(Region)),''),'Unassigned') ORDER BY count_value DESC
            """; }

    private String monthlySql() { return """
            SELECT period,count_value FROM (
              SELECT TOP 12 CONVERT(char(7),CreatedDate,120) period,COUNT_BIG(*) count_value
              FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External WHERE lobId=90 AND CreatedDate IS NOT NULL
              GROUP BY CONVERT(char(7),CreatedDate,120) ORDER BY period DESC
            ) recent ORDER BY period
            """; }

    private String onHoldSql() { return """
            SELECT COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'On Hold') name,COUNT_BIG(*) count_value
            FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External e
            LEFT JOIN [Toscana_I2P-SIT].dbo.TOSCANA3_STATUSMASTER sm ON sm.StatusID=e.StatusID AND sm.LobID=e.lobId
            WHERE e.lobId=90 AND LOWER(COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'')) LIKE '%hold%'
            GROUP BY COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'On Hold') ORDER BY count_value DESC
            """; }

    private String agingSql() { return """
            SELECT CASE WHEN e.Ageing >= 31 THEN '31+ days' WHEN e.Ageing >= 16 THEN '16-30 days'
                        WHEN e.Ageing >= 8 THEN '8-15 days' ELSE '0-7 days' END agingBand,
                   COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'Unknown') status,COUNT_BIG(*) count_value
            FROM [Toscana_I2P-SIT].dbo.AccountsPayable_External e
            LEFT JOIN [Toscana_I2P-SIT].dbo.TOSCANA3_STATUSMASTER sm ON sm.StatusID=e.StatusID AND sm.LobID=e.lobId
            WHERE e.lobId=90 AND e.Completed_Date IS NULL
            GROUP BY CASE WHEN e.Ageing >= 31 THEN '31+ days' WHEN e.Ageing >= 16 THEN '16-30 days'
                          WHEN e.Ageing >= 8 THEN '8-15 days' ELSE '0-7 days' END,
                     COALESCE(NULLIF(sm.StatusDescription,''),NULLIF(e.Status,''),'Unknown')
            ORDER BY agingBand,status
            """; }

    private String supplierSpendSql() { return """
            SELECT TOP 10 COALESCE(NULLIF(LTRIM(RTRIM(v.NAME1)),''),'Unknown vendor') vendorName,
                   h.WAERS currency,COUNT(DISTINCT h.EBELN) purchaseOrders,COALESCE(SUM(i.NETWR),0) totalSpend
            FROM [Toscana_I2P-SIT].sap.EKKO h
            JOIN [Toscana_I2P-SIT].sap.EKPO i ON i.EBELN=h.EBELN
            LEFT JOIN [Toscana_I2P-SIT].sap.LFA1 v ON v.LIFNR=h.LIFNR AND v.BUKRS=h.BUKRS
            GROUP BY COALESCE(NULLIF(LTRIM(RTRIM(v.NAME1)),''),'Unknown vendor'),h.WAERS
            ORDER BY totalSpend DESC
            """; }

    public record LobSummary(String name,long total,long completed,long open,double avgAgeing) {}
    public record CategoryCount(String name,long count) {}
    public record PeriodCount(String period,long count) {}
    public record AgingSummary(String agingBand,String status,long count) {}
    public record SupplierSpend(String vendorName,String currency,long purchaseOrders,BigDecimal totalSpend) {}
    public record TableInfo(String schema,String name,long rowCount,String category) {}
    public record Overview(String databaseName,Instant lastRefreshed,long totalWorkItems,long completedWorkItems,long openWorkItems,int activeLobs,double completionRate,List<LobSummary> lobs,List<CategoryCount> statuses,List<PeriodCount> monthlyVolume,List<CategoryCount> queues) {}
    public record Answer(String answer, Object rows, String explanation, String visualization,
                         com.toscana_insights.query.QueryService.Lineage lineage, String clarification) {
        public Answer(String answer, Object rows, String explanation) {
            this(answer, rows, explanation, "bar", null, null);
        }
        public static Answer clarify(String message) {
            return new Answer(message, List.of(), "No query was run.", "help", null, message);
        }
    }
}
