package com.toscana_insights.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.AppUserRepository;
import com.toscana_insights.workspace.Role;
import com.toscana_insights.workspace.Workspace;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/db/migration/V5__workbook_sample_reports.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = "DROP TABLE toscana_insights.workbook_sample_rows", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
class PublicationControllerTest {
    private static final String URL = "/api/published-dashboards";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkspaceRepository workspaces;
    @Autowired AppUserRepository users;
    @Autowired PublishedDashboardRepository dashboards;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    private UUID workspace() {
        String suffix = UUID.randomUUID().toString();
        return workspaces.save(new Workspace("Publication test", "publication-" + suffix)).getId();
    }

    private RequestPostProcessor user(UUID workspaceId, Role role) {
        String name = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@publication.test";
        users.save(new AppUser(workspaceId, name, "Test member", encoder.encode("test-password"), role));
        return httpBasic(name, "test-password");
    }

    private JsonNode publish(RequestPostProcessor actor, Map<String, Object> definition, int expected) throws Exception {
        String body = mvc.perform(post(URL).with(actor).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(definition)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String link(JsonNode publication) { return URL + "/token/" + publication.get("token").asText(); }

    @Test
    void publishingPreflightAllowsLocalFrontendButRejectsExternalOrigins() throws Exception {
        mvc.perform(options(URL)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5173"));
        mvc.perform(options(URL)
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void publicationPersistsFiltersAndRequiresBothSignInAndWorkspaceAccess() throws Exception {
        UUID workspaceId = workspace();
        RequestPostProcessor analyst = user(workspaceId, Role.ANALYST);
        RequestPostProcessor viewer = user(workspaceId, Role.VIEWER);
        JsonNode created = publish(analyst, Map.of("name", "  Operations snapshot  ", "kind", "executive", "days", 7, "unit", "Operations", "chart", "spend"), 201);
        assertThat(created.get("token").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(created.get("name").asText()).isEqualTo("Operations snapshot");
        assertThat(created.get("publishedAt").asText()).isNotBlank();
        assertThat(created.get("canRevoke").asBoolean()).isTrue();
        PublishedDashboard stored = dashboards.findById(UUID.fromString(created.get("id").asText())).orElseThrow();
        assertThat(stored.getToken()).isEqualTo(created.get("token").asText());
        assertThat(stored.getDays()).isEqualTo(7);
        assertThat(stored.getUnit()).isEqualTo("Operations");
        assertThat(stored.getChart()).isEqualTo("spend");
        JsonNode viewed = json.readTree(mvc.perform(get(link(created)).with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(viewed.get("canRevoke").asBoolean()).isFalse();
        assertThat(viewed.get("id")).isEqualTo(created.get("id"));
        mvc.perform(get(link(created))).andExpect(status().isUnauthorized());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(delete(URL + "/" + created.get("id").asText())).andExpect(status().isUnauthorized());
        RequestPostProcessor outsider = user(workspace(), Role.ADMIN);
        mvc.perform(get(link(created)).with(outsider)).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/" + created.get("id").asText()).with(outsider)).andExpect(status().isNotFound());
        assertThat(mvc.perform(get(URL).with(outsider)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
        mvc.perform(get(URL + "/token/" + "x".repeat(43)).with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/token/bad-token").with(viewer)).andExpect(status().isNotFound());
    }

    @Test
    void viewerCannotPublishOrRevokeAndUnrelatedAnalystCannotRevoke() throws Exception {
        UUID workspaceId = workspace();
        RequestPostProcessor owner = user(workspaceId, Role.ANALYST);
        RequestPostProcessor viewer = user(workspaceId, Role.VIEWER);
        Map<String, Object> definition = Map.of("name", "Queue report", "kind", "workbook", "reportId", "queue-management", "chart", "donut");
        JsonNode created = publish(owner, definition, 201);
        publish(viewer, definition, 403);
        String deleteUrl = URL + "/" + created.get("id").asText();
        mvc.perform(delete(deleteUrl).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(delete(deleteUrl).with(user(workspaceId, Role.ANALYST))).andExpect(status().isForbidden());
        mvc.perform(get(link(created)).with(viewer)).andExpect(status().isOk());
        mvc.perform(delete(deleteUrl).with(owner)).andExpect(status().isNoContent());
        mvc.perform(get(link(created)).with(viewer)).andExpect(status().isNotFound());
        assertThat(dashboards.existsById(UUID.fromString(created.get("id").asText()))).isFalse();
        assertThat(mvc.perform(get(URL).with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void workspaceAdminsCanRevokeAndAuditNeverContainsTokens() throws Exception {
        UUID workspaceId = workspace();
        RequestPostProcessor publisher = user(workspaceId, Role.ANALYST);
        for (Role role : List.of(Role.ADMIN, Role.OWNER)) {
            RequestPostProcessor admin = user(workspaceId, role);
            JsonNode created = publish(publisher, Map.of("name", "Executive", "kind", "executive"), 201);
            String view = mvc.perform(get(link(created)).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(view).get("canRevoke").asBoolean()).isTrue();
            mvc.perform(delete(URL + "/" + created.get("id").asText()).with(admin)).andExpect(status().isNoContent());
            mvc.perform(get(link(created)).with(admin)).andExpect(status().isNotFound());
            String audit = mvc.perform(get("/api/audit-events").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(audit).contains("PUBLISH", "REVOKE").doesNotContain(created.get("token").asText());
        }
    }

    @Test
    void rejectsInvalidAndUnboundedDashboardDefinitions() throws Exception {
        RequestPostProcessor analyst = user(workspace(), Role.ANALYST);
        Map<String, Object> base = Map.of("name", "Executive", "kind", "executive");
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("name", " "), Map.of("name", "x".repeat(121)), Map.of("kind", "other"),
                Map.of("days", 365), Map.of("unit", "private-data"), Map.of("chart", "<script>"),
                Map.of("reportId", "queue-management"), Map.of("kind", "workbook"),
                Map.of("kind", "workbook", "reportId", "unknown"),
                Map.of("kind", "workbook", "reportId", "utilization", "chart", "volume"))) {
            Map<String, Object> definition = new HashMap<>(base);
            definition.putAll(invalid);
            publish(analyst, definition, 400);
        }
    }

    @Test
    void supportsAllFiveWorkbookReportsWithIndependentOpaqueTokens() throws Exception {
        RequestPostProcessor analyst = user(workspace(), Role.ANALYST);
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (String report : List.of("queue-management", "on-hold", "csat-dashboard", "savings-collection", "utilization")) {
            JsonNode created = publish(analyst, Map.of("name", report, "kind", "workbook", "reportId", report), 201);
            assertThat(created.get("reportId").asText()).isEqualTo(report);
            assertThat(created.get("chart").asText()).isEqualTo("bar");
            assertThat(tokens.add(created.get("token").asText())).isTrue();
            mvc.perform(get(link(created)).with(analyst)).andExpect(status().isOk());
        }
    }

    private Map<String, Object> templateDefinition(String name, String chart) {
        return Map.of("version", 1, "name", name, "description", "Reusable queue layout", "widgets", List.of(Map.of(
                "id", "queue", "title", "Queue results", "sourceType", "workbook", "sourceId", "queue-management",
                "chart", chart, "labelKey", "date", "valueKey", "resolved", "columns", List.of(), "width", "half")));
    }

    private JsonNode saveTemplate(RequestPostProcessor actor) throws Exception {
        return json.readTree(mvc.perform(post("/api/report-templates").with(actor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(templateDefinition("Original layout", "bar"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void templatePublicationFreezesLayoutAfterEditsAndDeletionButReloadsSourceData() throws Exception {
        UUID workspaceId = workspace();
        RequestPostProcessor analyst = user(workspaceId, Role.ANALYST);
        RequestPostProcessor viewer = user(workspaceId, Role.VIEWER);
        JsonNode template = saveTemplate(analyst);
        String templateId = template.get("id").asText();
        JsonNode publication = publish(analyst, Map.of("name", "Published queue", "kind", "template", "reportId", templateId), 201);
        PublishedDashboard stored = dashboards.findById(UUID.fromString(publication.get("id").asText())).orElseThrow();
        JsonNode snapshot = json.readTree(stored.getTemplateConfiguration());
        assertThat(snapshot.path("name").asText()).isEqualTo("Original layout");
        assertThat(snapshot.path("widgets").get(0).path("chart").asText()).isEqualTo("bar");
        assertThat(stored.getTemplateConfiguration()).doesNotContain("openCases", "2026-10-02");
        String dataUrl = link(publication) + "/data";
        JsonNode original = json.readTree(mvc.perform(get(dataUrl).with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(original.path("definition")).isEqualTo(snapshot);
        assertThat(original.path("widgets").get(0).path("rows").get(0).path("resolved").asInt()).isEqualTo(79);
        mvc.perform(put("/api/report-templates/" + templateId).with(analyst).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(templateDefinition("Edited layout", "line")))).andExpect(status().isOk());
        JsonNode afterEdit = json.readTree(mvc.perform(get(dataUrl).with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(afterEdit.path("definition")).isEqualTo(snapshot);
        mvc.perform(delete("/api/report-templates/" + templateId).with(analyst)).andExpect(status().isNoContent());
        String sourceRow = jdbc.queryForObject("SELECT data_json FROM toscana_insights.workbook_sample_rows WHERE report_id = 'queue-management' AND row_order = 1", String.class);
        try {
            jdbc.update("UPDATE toscana_insights.workbook_sample_rows SET data_json = ? WHERE report_id = 'queue-management' AND row_order = 1", sourceRow.replace("\"resolved\":79", "\"resolved\":777"));
            JsonNode refreshed = json.readTree(mvc.perform(get(dataUrl).with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(refreshed.path("definition")).isEqualTo(snapshot);
            assertThat(refreshed.path("widgets").get(0).path("rows").get(0).path("resolved").asInt()).isEqualTo(777);
        } finally {
            jdbc.update("UPDATE toscana_insights.workbook_sample_rows SET data_json = ? WHERE report_id = 'queue-management' AND row_order = 1", sourceRow);
        }
    }

    @Test
    void templatePublicationDataRequiresLoginWorkspaceAndActiveToken() throws Exception {
        UUID workspaceId = workspace();
        RequestPostProcessor analyst = user(workspaceId, Role.ANALYST);
        RequestPostProcessor viewer = user(workspaceId, Role.VIEWER);
        RequestPostProcessor outsider = user(workspace(), Role.ADMIN);
        String templateId = saveTemplate(analyst).get("id").asText();
        Map<String, Object> definition = Map.of("name", "Protected layout", "kind", "template", "reportId", templateId);
        publish(viewer, definition, 403);
        publish(outsider, definition, 404);
        publish(analyst, Map.of("name", "Invalid", "kind", "template", "reportId", "unknown"), 400);
        publish(analyst, Map.of("name", "Missing", "kind", "template"), 400);
        JsonNode publication = publish(analyst, definition, 201);
        String dataUrl = link(publication) + "/data";
        mvc.perform(get(dataUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(dataUrl).with(outsider)).andExpect(status().isNotFound());
        mvc.perform(get(dataUrl).with(viewer)).andExpect(status().isOk());
        mvc.perform(get(URL + "/token/not-a-token/data").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/" + publication.get("id").asText()).with(analyst)).andExpect(status().isNoContent());
        mvc.perform(get(dataUrl).with(viewer)).andExpect(status().isNotFound());
        JsonNode executive = publish(analyst, Map.of("name", "Executive", "kind", "executive"), 201);
        mvc.perform(get(link(executive) + "/data").with(viewer)).andExpect(status().isBadRequest());
    }
}
