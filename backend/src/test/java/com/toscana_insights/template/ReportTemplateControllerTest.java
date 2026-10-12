package com.toscana_insights.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.workspace.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.toscana_insights.toscana.ToscanaAnalyticsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/db/migration/V5__workbook_sample_reports.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = "DROP TABLE toscana_insights.workbook_sample_rows", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
class ReportTemplateControllerTest {
    private static final String BASE = "/api/report-templates";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired PasswordEncoder encoder;
    @MockitoBean ToscanaAnalyticsService connected;

    @Test
    void persistsDefinitionAndEnforcesOwnerAdminAndWorkspaceBoundaries() throws Exception {
        var owner = user(Role.ANALYST, workspace());
        var peer = user(Role.ANALYST, workspace());
        var viewer = user(Role.VIEWER, workspace());
        UUID other = workspaces.save(new Workspace("Other", "other-" + UUID.randomUUID())).getId();
        var outsider = user(Role.ADMIN, other);
        JsonNode saved = postDefinition(BASE, definition("Queue template"), owner, 201);
        String path = BASE + "/" + saved.path("id").asText();
        assertThat(saved.path("canEdit").asBoolean()).isTrue();
        assertThat(saved.path("createdAt").asText()).isNotBlank();
        mvc.perform(get(path).with(peer)).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false));
        mvc.perform(get(path).with(viewer)).andExpect(status().isOk());
        mvc.perform(get(path + "/run").with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.widgets[0].rows.length()").value(8));
        mvc.perform(put(path).with(peer).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(definition("Peer edit"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).with(peer)).andExpect(status().isForbidden());
        mvc.perform(get(path).with(outsider)).andExpect(status().isNotFound());
        mvc.perform(get(path + "/run").with(outsider)).andExpect(status().isNotFound());
        mvc.perform(delete(path).with(outsider)).andExpect(status().isNotFound());
        mvc.perform(get(BASE).with(outsider)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put(path).with(owner).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(definition("Renamed"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.definition.name").value("Renamed"));
        mvc.perform(put(path).with(admin()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(definition("Admin edit"))))
                .andExpect(status().isOk());
        mvc.perform(delete(path).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get(path).with(owner)).andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthenticationAndAnalystRoleForMutations() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/sources")).andExpect(status().isUnauthorized());
        var viewer = user(Role.VIEWER, workspace());
        postDefinition(BASE, definition("Viewer edit"), viewer, 403);
        String id = postDefinition(BASE, definition("Admin template"), admin(), 201).path("id").asText();
        mvc.perform(put(BASE + "/" + id).with(viewer).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(definition("Viewer edit")))).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/" + id).with(viewer)).andExpect(status().isForbidden());
        postDefinition(BASE + "/preview", definition("Viewer preview"), viewer, 200);
        mvc.perform(delete(BASE + "/" + id).with(admin())).andExpect(status().isNoContent());
    }

    @Test
    void returnsOnlyIdBasedSourcesAndValidatesFieldsBeforeSaving() throws Exception {
        mvc.perform(get(BASE + "/sources").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("workbook")).andExpect(jsonPath("$[0].sample").value(true));
        mvc.perform(get(BASE + "/sources/workbook/queue-management").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(8)).andExpect(jsonPath("$.sample").value(true));
        mvc.perform(get(BASE + "/sources/sql/anything").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/sources/report/not-a-uuid").with(admin())).andExpect(status().isBadRequest());
        var invalid = mapper.valueToTree(definition("Invalid"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("widgets").get(0)).put("valueKey", "date");
        postDefinition(BASE, invalid, admin(), 400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("widgets").get(0)).put("valueKey", "missing");
        postDefinition(BASE, invalid, admin(), 400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("widgets").get(0)).put("sourceId", "missing");
        postDefinition(BASE, invalid, admin(), 404);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("widgets").get(0)).put("sourceId", "queue-management").put("chart", "table");
        postDefinition(BASE, invalid, admin(), 400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("widgets").get(0)).putArray("columns").add("date").add("resolved");
        postDefinition(BASE + "/preview", invalid, admin(), 200);
    }

    @Test
    void rejectsMalformedOversizedAndDuplicateDefinitions() throws Exception {
        postDefinition(BASE, Map.of("version", 2, "name", "Invalid", "widgets", List.of()), admin(), 400);
        postDefinition(BASE, Map.of("version", 1, "name", " ", "widgets", List.of(widget())), admin(), 400);
        postDefinition(BASE, Map.of("version", 1, "name", "Duplicates", "widgets", List.of(widget(), widget())), admin(), 400);
        postDefinition(BASE, Map.of("version", 1, "name", "Too many", "widgets", java.util.Collections.nCopies(13, widget())), admin(), 400);
        mvc.perform(post(BASE).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest());
        var invalid = mapper.valueToTree(definition("Invalid chart"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("widgets").get(0)).put("chart", "<script>");
        postDefinition(BASE, invalid, admin(), 400);
        var largeWidgets = new java.util.ArrayList<ReportTemplateDtos.Widget>();
        for (int i = 0; i < 12; i++) {
            largeWidgets.add(new ReportTemplateDtos.Widget("w" + i, "Table", "workbook", "queue-management", "table", null, null,
                    java.util.Collections.nCopies(100, "界".repeat(128)), "full"));
        }
        JsonNode response = postDefinition(BASE, new ReportTemplateDtos.Definition(1, "Oversized", "", largeWidgets), admin(), 400);
        assertThat(response.path("detail").asText()).contains("64 KB");
    }

    private UUID workspace() { return workspaces.findBySlug("default").orElseThrow().getId(); }
    private RequestPostProcessor admin() { return httpBasic("demo@toscana-insights.local", "demo123!"); }
    private RequestPostProcessor user(Role role, UUID workspace) {
        String username = "template-" + UUID.randomUUID() + "@test.local";
        users.save(new AppUser(workspace, username, "Template tester", encoder.encode("test-password"), role));
        return httpBasic(username, "test-password");
    }
    private ReportTemplateDtos.Widget widget() {
        return new ReportTemplateDtos.Widget("queue", "Resolved cases", "workbook", "queue-management", "bar", "date", "resolved", List.of(), "half");
    }
    private ReportTemplateDtos.Definition definition(String name) {
        return new ReportTemplateDtos.Definition(1, name, "A reusable report layout", List.of(widget()));
    }
    private JsonNode postDefinition(String path, Object definition, RequestPostProcessor who, int status) throws Exception {
        String body = mvc.perform(post(path).with(who).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(definition)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? mapper.nullNode() : mapper.readTree(body);
    }
}
