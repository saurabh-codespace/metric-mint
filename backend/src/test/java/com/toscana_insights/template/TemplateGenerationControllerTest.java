package com.toscana_insights.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.toscana.ToscanaAnalyticsService;
import com.toscana_insights.workspace.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.ai.provider=disabled")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/db/migration/V5__workbook_sample_reports.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = "DROP TABLE toscana_insights.workbook_sample_rows", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
class TemplateGenerationControllerTest {
    private static final String BASE = "/api/report-templates";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired PasswordEncoder encoder;
    @MockitoBean ToscanaAnalyticsService connected;

    @BeforeEach
    void liveReports() {
        when(connected.reports()).thenReturn(List.of(
                Map.of("id", "invoice-intake", "title", "Invoice Intake Trend", "description", "Latest12 creation months", "status", "READY", "generatedAt", Instant.now(), "data", List.of(Map.of("period", "2026-10", "count", 42))),
                Map.of("id", "invoice-status", "title", "Invoice Status Distribution", "description", "AP LOB90 statuses", "status", "READY", "generatedAt", Instant.now(), "data", List.of(Map.of("name", "Completed", "count", 30)))));
    }

    private RequestPostProcessor admin() { return httpBasic("demo@toscana-insights.local", "demo123!"); }
    private RequestPostProcessor user(Role role, UUID workspace) {
        String name = "generation-" + UUID.randomUUID();
        users.save(new AppUser(workspace, name, name, encoder.encode("generation-test"), role));
        return httpBasic(name, "generation-test");
    }
    private JsonNode postJson(String path, Object body, RequestPostProcessor actor, int status) throws Exception {
        return json.readTree(mvc.perform(post(path).with(actor).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void liveStarterCanBeEditedSavedPublishedAndRerunsViewerSourcePermissions() throws Exception {
        JsonNode generated = postJson(BASE + "/generate", Map.of("prompt", "Create an invoice dashboard with monthly volume and status distribution", "dataMode", "live"), admin(), 200);
        assertThat(generated.path("aiGenerated").asBoolean()).isFalse();
        assertThat(generated.path("result").path("widgets")).hasSize(2);
        assertThat(generated.path("result").path("widgets").get(0).path("sample").asBoolean()).isFalse();
        JsonNode definition = generated.path("result").path("definition");
        JsonNode saved = postJson(BASE, definition, admin(), 201);
        String id = saved.path("id").asText();
        mvc.perform(get(BASE + "/" + id + "/run").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.widgets[0].rows[0].count").value(42));
        JsonNode publication = postJson("/api/published-dashboards", Map.of("name", "Live generated dashboard", "kind", "template", "reportId", id), admin(), 201);
        String data = "/api/published-dashboards/token/" + publication.path("token").asText() + "/data";
        mvc.perform(get(data).with(admin())).andExpect(status().isOk());
        UUID workspace = workspaces.findBySlug("default").orElseThrow().getId();
        var viewer = user(Role.VIEWER, workspace);
        var analyst = user(Role.ANALYST, workspace);
        for (var actor : List.of(viewer, analyst)) {
            postJson(BASE + "/preview", definition, actor, 403);
            mvc.perform(get(BASE + "/" + id + "/run").with(actor)).andExpect(status().isForbidden());
            mvc.perform(get(data).with(actor)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/sources/connected/invoice-intake").with(actor)).andExpect(status().isForbidden());
        }
        var outsider = user(Role.ADMIN, workspaces.save(new Workspace("Other", "generation-" + UUID.randomUUID())).getId());
        mvc.perform(get(BASE + "/sources/connected/invoice-intake").with(outsider)).andExpect(status().isForbidden());
        mvc.perform(get(data).with(outsider)).andExpect(status().isNotFound());
        postJson("/api/published-dashboards", Map.of("name", "Not permitted", "kind", "template", "reportId", id), analyst, 403);
        JsonNode unsupported = postJson(BASE + "/generate", Map.of("prompt", "Show monthly invoice volume for 2020 only", "dataMode", "live"), admin(), 200);
        assertThat(unsupported.hasNonNull("result")).isFalse();
        assertThat(unsupported.path("clarification").asText()).isNotBlank();
    }

    @Test
    void sampleModeIsExplicitAndGenerationEndpointsEnforceRolesAndLimits() throws Exception {
        UUID workspace = workspaces.findBySlug("default").orElseThrow().getId();
        var analyst = user(Role.ANALYST, workspace);
        var viewer = user(Role.VIEWER, workspace);
        Map<String, Object> sample = Map.of("prompt", "Create a dashboard with queue management and utilization", "dataMode", "sample");
        JsonNode generated = postJson(BASE + "/generate", sample, analyst, 200);
        assertThat(generated.path("result").path("widgets")).hasSize(2);
        for (JsonNode widget : generated.path("result").path("widgets")) assertThat(widget.path("sample").asBoolean()).isTrue();
        postJson(BASE + "/generate", sample, viewer, 403);
        mvc.perform(get(BASE + "/ai/status").with(analyst)).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get(BASE + "/ai/status").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/ai/status")).andExpect(status().isUnauthorized());
        postJson(BASE + "/generate", Map.of("prompt", "x".repeat(2001), "dataMode", "sample"), analyst, 400);
        postJson(BASE + "/generate", Map.of("prompt", " ", "dataMode", "sample"), analyst, 400);
        postJson(BASE + "/generate", Map.of("prompt", "Create queue management", "dataMode", "mixed"), analyst, 400);
        JsonNode noLive = postJson(BASE + "/generate", Map.of("prompt", "Create queue management", "dataMode", "live"), analyst, 200);
        assertThat(noLive.hasNonNull("result")).isFalse();
    }

    @Test
    void sampleGenerationAndSourceListingDoNotDependOnOperationalDatabase() throws Exception {
        when(connected.reports()).thenThrow(new IllegalStateException("Operational source unavailable"));
        JsonNode sample = postJson(BASE + "/generate", Map.of("prompt", "Create a dashboard with queue management and utilization", "dataMode", "sample"), admin(), 200);
        assertThat(sample.path("result").path("widgets")).hasSize(2);
        mvc.perform(get(BASE + "/sources").with(admin())).andExpect(status().isOk());
        verify(connected, never()).reports();
    }
}
