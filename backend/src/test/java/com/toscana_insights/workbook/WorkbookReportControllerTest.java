package com.toscana_insights.workbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/db/migration/V5__workbook_sample_reports.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = "DROP TABLE toscana_insights.workbook_sample_rows", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
class WorkbookReportControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/workbook-reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workbook-reports/utilization")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void servesFiveSampleReportsWithReconciledMetrics() throws Exception {
        JsonNode reports = mapper.readTree(mvc.perform(get("/api/workbook-reports"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(5, reports.size());
        for (JsonNode report : reports) {
            assertEquals("SAMPLE", report.path("status").asText());
            assertEquals("SQL_SERVER", report.path("sourceType").asText());
            assertEquals("2026-10-09", report.path("sampleAsOf").asText());
            assertEquals(8, report.path("recordCount").asInt());
            assertEquals(8, report.path("data").size());
            for (JsonNode row : report.path("data")) {
                assertTrue(row.hasNonNull(report.path("labelKey").asText()));
                assertTrue(row.path(report.path("valueKey").asText()).isNumber());
            }
        }

        JsonNode queue = reports.get(0);
        assertEquals("Queue Management Dashboard", queue.path("title").asText());
        assertEquals(2, queue.path("workbookRow").asInt());
        assertKpi(queue, 0, sum(queue, "received"));
        assertKpi(queue, 1, sum(queue, "resolved"));
        assertKpi(queue, 2, 124 + sum(queue, "received") - sum(queue, "resolved"));
        double backlog = 124;
        for (JsonNode row : queue.path("data")) {
            backlog += row.path("received").asDouble() - row.path("resolved").asDouble();
            assertEquals(backlog, row.path("openCases").asDouble());
            assertTrue(row.path("outsideSla").asDouble() <= backlog);
        }
        assertKpi(queue, 3, weighted(queue, "cycleHours", "resolved"));

        JsonNode hold = reports.get(1);
        assertKpi(hold, 0, sum(hold, "heldCases"));
        assertKpi(hold, 1, sum(hold, "over48Hours"));
        assertKpi(hold, 2, weighted(hold, "averageHoldHours", "heldCases"));
        for (JsonNode row : hold.path("data")) {
            assertTrue(row.path("over48Hours").asInt() <= row.path("heldCases").asInt());
        }

        JsonNode csat = reports.get(2);
        assertKpi(csat, 0, sum(csat, "satisfied") / sum(csat, "responses") * 100);
        assertKpi(csat, 1, sum(csat, "responses"));
        assertKpi(csat, 2, sum(csat, "satisfied"));
        for (JsonNode row : csat.path("data")) {
            assertEquals(row.path("satisfied").asDouble() / row.path("responses").asDouble() * 100,
                    row.path("scorePercent").asDouble(), 0.0051);
        }

        JsonNode savings = reports.get(3);
        assertKpi(savings, 0, sum(savings, "automationUsd") + sum(savings, "processUsd"));
        assertKpi(savings, 1, sum(savings, "automationUsd"));
        assertKpi(savings, 2, sum(savings, "hoursSaved"));
        for (JsonNode row : savings.path("data")) {
            assertEquals(row.path("automationUsd").asDouble() + row.path("processUsd").asDouble(),
                    row.path("totalUsd").asDouble());
        }

        JsonNode utilization = reports.get(4);
        assertKpi(utilization, 0, sum(utilization, "billableHours") / sum(utilization, "availableHours") * 100);
        assertKpi(utilization, 1, sum(utilization, "billableHours"));
        assertKpi(utilization, 2, sum(utilization, "availableHours"));
        assertKpi(utilization, 3, sum(utilization, "trainingHours"));
        for (JsonNode row : utilization.path("data")) {
            assertTrue(row.path("billableHours").asDouble() + row.path("trainingHours").asDouble()
                    <= row.path("availableHours").asDouble());
            assertEquals(row.path("billableHours").asDouble() / row.path("availableHours").asDouble() * 100,
                    row.path("utilizationPercent").asDouble(), 0.0051);
        }
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void retrievesIndividualReportAndRejectsUnknownId() throws Exception {
        JsonNode report = mapper.readTree(mvc.perform(get("/api/workbook-reports/utilization"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("Utilization Dashboard - PBI", report.path("title").asText());
        mvc.perform(get("/api/workbook-reports/missing")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "UNRELATED")
    void requiresWorkspaceMemberRole() throws Exception {
        mvc.perform(get("/api/workbook-reports")).andExpect(status().isForbidden());
    }

    private static double sum(JsonNode report, String key) {
        return StreamSupport.stream(report.path("data").spliterator(), false)
                .mapToDouble(row -> row.path(key).asDouble()).sum();
    }

    private static double weighted(JsonNode report, String key, String weight) {
        return StreamSupport.stream(report.path("data").spliterator(), false)
                .mapToDouble(row -> row.path(key).asDouble() * row.path(weight).asDouble()).sum() / sum(report, weight);
    }

    private static void assertKpi(JsonNode report, int index, double expected) {
        assertEquals(expected, report.path("kpis").get(index).path("value").asDouble(), 0.0051);
    }
}
