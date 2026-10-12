package com.toscana_insights.workbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.common.NotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkbookReportService {
    public record Kpi(String label, double value, String unit) {}
    public record WorkbookReport(String id, String title, String description, String category,
            String frequency, int workbookRow, String chartType, String status, String sourceType,
            int recordCount, String generatedAt, String sampleAsOf, List<Map<String, Object>> data,
            List<Kpi> kpis, String valueKey, String labelKey) {}
    private record Definition(String id, String title, String description, String category,
            String frequency, int workbookRow, String chartType, String valueKey, String labelKey) {}

    private static final List<Definition> REPORTS = List.of(
            new Definition("queue-management", "Queue Management Dashboard",
                    "Daily case intake, resolution, open backlog and SLA exceptions.", "Operations", "Daily", 2,
                    "bar", "resolved", "date"),
            new Definition("on-hold", "On Hold Report",
                    "Paused cases by request category, hold duration and aging.", "Service delivery", "Daily", 3,
                    "bar", "heldCases", "category"),
            new Definition("csat-dashboard", "CSAT Dashboard - Power BI (Development, Enhancement, Maintenance)",
                    "Daily customer satisfaction responses and satisfied-response rate.", "Experience", "Daily", 17,
                    "line", "scorePercent", "date"),
            new Definition("savings-collection", "Savings data collection",
                    "Weekly automation and process savings, reported in sample USD.", "Finance", "Weekly", 18,
                    "bar", "totalUsd", "week"),
            new Definition("utilization", "Utilization Dashboard - PBI",
                    "Team capacity, billable work and training hours for the snapshot week.", "Workforce", "Ad-hoc", 40,
                    "bar", "utilizationPercent", "team"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkbookReportService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<WorkbookReport> list() {
        return REPORTS.stream().map(this::load).toList();
    }

    public WorkbookReport get(String id) {
        return load(REPORTS.stream().filter(report -> report.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Workbook report", id)));
    }

    private WorkbookReport load(Definition report) {
        List<Map<String, Object>> data = jdbc.query(
                "SELECT data_json FROM toscana_insights.workbook_sample_rows WHERE report_id = ? ORDER BY row_order",
                (rs, rowNum) -> parse(rs.getString("data_json")), report.id());
        // shortcut: fixed demo snapshot; add ingestion and row-level dates when connecting production reports.
        return new WorkbookReport(report.id(), report.title(), report.description(), report.category(),
                report.frequency(), report.workbookRow(), report.chartType(), "SAMPLE", "SQL_SERVER", data.size(),
                "2026-10-09T18:00:00Z", "2026-10-09", data, kpis(report.id(), data), report.valueKey(), report.labelKey());
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid workbook sample row", e);
        }
    }

    private List<Kpi> kpis(String id, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        return switch (id) {
            case "queue-management" -> List.of(
                    kpi("Cases received", sum(rows, "received"), ""),
                    kpi("Cases resolved", sum(rows, "resolved"), ""),
                    kpi("Open cases", number(rows.getLast(), "openCases"), ""),
                    kpi("Average cycle time", weightedAverage(rows, "cycleHours", "resolved"), "hours"));
            case "on-hold" -> List.of(
                    kpi("Cases on hold", sum(rows, "heldCases"), ""),
                    kpi("Held over 48 hours", sum(rows, "over48Hours"), ""),
                    kpi("Average hold time", weightedAverage(rows, "averageHoldHours", "heldCases"), "hours"));
            case "csat-dashboard" -> List.of(
                    kpi("Satisfaction", ratio(sum(rows, "satisfied") * 100, sum(rows, "responses")), "%"),
                    kpi("Responses", sum(rows, "responses"), ""),
                    kpi("Satisfied responses", sum(rows, "satisfied"), ""));
            case "savings-collection" -> List.of(
                    kpi("Total savings", sum(rows, "totalUsd"), "USD"),
                    kpi("Automation savings", sum(rows, "automationUsd"), "USD"),
                    kpi("Hours saved", sum(rows, "hoursSaved"), "hours"));
            case "utilization" -> List.of(
                    kpi("Utilization", ratio(sum(rows, "billableHours") * 100, sum(rows, "availableHours")), "%"),
                    kpi("Billable hours", sum(rows, "billableHours"), "hours"),
                    kpi("Available hours", sum(rows, "availableHours"), "hours"),
                    kpi("Training hours", sum(rows, "trainingHours"), "hours"));
            default -> throw new IllegalArgumentException("Unknown workbook report");
        };
    }

    private static Kpi kpi(String label, double value, String unit) {
        return new Kpi(label, Math.round(value * 100) / 100.0, unit);
    }

    private static double sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(row -> number(row, key)).sum();
    }

    private static double weightedAverage(List<Map<String, Object>> rows, String value, String weight) {
        return ratio(rows.stream().mapToDouble(row -> number(row, value) * number(row, weight)).sum(), sum(rows, weight));
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static double number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }
}
