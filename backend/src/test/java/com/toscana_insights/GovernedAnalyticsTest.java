package com.toscana_insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.toscana_insights.datasource.DataSourceConnection;
import com.toscana_insights.datasource.JdbcSourceFactory;
import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.AppUserRepository;
import com.toscana_insights.workspace.Role;
import com.toscana_insights.workspace.Workspace;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "app.ai.provider=disabled")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovernedAnalyticsTest.RemoteDb.class)
class GovernedAnalyticsTest {
    static final String REMOTE_URL = "jdbc:h2:mem:remote_customer;DB_CLOSE_DELAY=-1";

    /** Replaces the PostgreSQL/SQL Server connector with an in-memory H2 "customer database". */
    @TestConfiguration
    static class RemoteDb {
        @Bean @Primary
        JdbcSourceFactory jdbcSourceFactory() {
            return new JdbcSourceFactory() {
                @Override public Connection open(DataSourceConnection source, String password, boolean trust) throws SQLException {
                    if (!"s3cret-pw".equals(password)) throw new SQLException("bad credentials");
                    Connection c = DriverManager.getConnection(REMOTE_URL);
                    c.setReadOnly(true);
                    return c;
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkspaceRepository workspaces;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    static final String ADMIN_USER = "demo@toscana-insights.local";
    static final String ADMIN_PW = "demo123!";
    static final String ORDERS_CSV = """
            order_id,region,order_date,amount,discount,cost,customer_id
            1,EMEA,2026-01-15,100.00,10.00,40,c1
            2,EMEA,2026-02-10,200.00,0,80,c2
            3,APAC,2026-02-12,300.50,50.50,120,c1
            4,AMER,2026-03-01,150,15,60,c3
            6,EMEA,2026-03-05
            """;

    // ---------- helpers ----------
    RequestPostProcessor admin() { return httpBasic(ADMIN_USER, ADMIN_PW); }

    JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, int expected) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? json.nullNode() : json.readTree(body);
    }

    JsonNode post(String url, Object body, RequestPostProcessor who, int expected) throws Exception {
        return call(MockMvcRequestBuilders.post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expected);
    }

    JsonNode get(String url, RequestPostProcessor who) throws Exception { return call(MockMvcRequestBuilders.get(url).with(who), 200); }

    JsonNode upload(String sourceId, String table, String mode, String csv, RequestPostProcessor who) throws Exception {
        return call(multipart("/api/data-sources/" + sourceId + "/upload").file(new MockMultipartFile("file", "d.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("table", table).param("mode", mode).with(who), 200);
    }

    String uid() { return UUID.randomUUID().toString().substring(0, 8); }

    RequestPostProcessor newUser(Role role, String password) {
        String name = role.name().toLowerCase() + "-" + uid() + "@test.local";
        Workspace ws = workspaces.findBySlug("default").orElseThrow();
        users.save(new AppUser(ws.getId(), name, name, encoder.encode(password), role));
        return httpBasic(name, password);
    }

    record Fixture(String sourceId, String table, String tableId, String metricKey, String metricId, String suffix) {}

    /** CSV source with an orders table (one bad row) and an approved, verified net_revenue metric. */
    Fixture ordersFixture() throws Exception {
        String s = uid();
        String sourceId = post("/api/data-sources", Map.of("name", "csv-" + s, "type", "CSV_UPLOAD"), admin(), 201).get("id").asText();
        String table = "orders_" + s;
        JsonNode run = upload(sourceId, table, "REPLACE", ORDERS_CSV, admin());
        assertThat(run.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(run.get("rowsLoaded").asInt()).isEqualTo(4);
        assertThat(run.get("rowsRejected").asInt()).isEqualTo(1);
        assertThat(run.get("badRows").get(0).get("reason").asText()).contains("expected 7 columns");

        String tableId = null;
        for (JsonNode t : get("/api/catalog/tables", admin())) if (t.get("name").asText().equals(table)) tableId = t.get("id").asText();
        assertThat(tableId).isNotNull();

        String key = "net_revenue_" + s;
        Map<String, Object> metric = Map.ofEntries(Map.entry("name", "Net revenue " + s), Map.entry("keyName", key), Map.entry("description", "Amount less discount"),
                Map.entry("aggregation", "SUM"), Map.entry("sourceField", "amount - discount"), Map.entry("active", true), Map.entry("tableId", tableId),
                Map.entry("dateColumn", "order_date"), Map.entry("synonyms", List.of("revenue " + s)), Map.entry("baselineValue", 675));
        String metricId = post("/api/metrics", metric, admin(), 201).get("id").asText();
        post("/api/metrics/" + metricId + "/approve", Map.of(), admin(), 200);
        JsonNode verified = post("/api/metrics/" + metricId + "/verify", Map.of(), admin(), 200);
        assertThat(verified.get("passed").asBoolean()).isTrue();
        return new Fixture(sourceId, table, tableId, key, metricId, s);
    }

    JsonNode query(Fixture f, List<String> dims, RequestPostProcessor who) throws Exception {
        return post("/api/query", Map.of("metric", f.metricKey(), "dimensions", dims), who, 200);
    }

    // ---------- tests ----------
    @Test
    void csvImportApproveVerifyQueryAndLineage() throws Exception {
        Fixture f = ordersFixture();
        JsonNode result = query(f, List.of("region"), admin());
        assertThat(result.get("label").asText()).isEqualTo("VERIFIED");
        Map<String, Double> byRegion = new java.util.HashMap<>();
        for (JsonNode r : result.get("rows")) byRegion.put(r.get("region").asText(), r.get(f.metricKey()).asDouble());
        assertThat(byRegion).containsEntry("EMEA", 290.0).containsEntry("APAC", 250.0).containsEntry("AMER", 135.0);
        JsonNode lineage = result.get("lineage");
        assertThat(lineage.get("sql").asText()).contains("GROUP BY").doesNotContain("EMEA");
        assertThat(lineage.get("metricDefinition").asText()).isEqualTo("SUM(amount - discount)");
        assertThat(lineage.get("tables").get(0).asText()).isEqualTo(f.table());

        // an edit makes the metric a draft again: still queryable but labelled exploratory with a warning
        Map<String, Object> edit = Map.ofEntries(Map.entry("name", "Net revenue " + f.suffix()), Map.entry("keyName", f.metricKey()), Map.entry("description", "Amount only"),
                Map.entry("aggregation", "SUM"), Map.entry("sourceField", "amount"), Map.entry("active", true), Map.entry("tableId", f.tableId()), Map.entry("baselineValue", 675));
        call(put("/api/metrics/" + f.metricId()).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)), 200);
        JsonNode draft = query(f, List.of(), admin());
        assertThat(draft.get("label").asText()).isEqualTo("EXPLORATORY");
        assertThat(draft.get("warnings").toString()).contains("draft");

        // compare and roll back to the last approved+verified state
        JsonNode versions = get("/api/metrics/" + f.metricId() + "/versions", admin());
        assertThat(versions.size()).isGreaterThanOrEqualTo(4);
        JsonNode diff = get("/api/metrics/" + f.metricId() + "/diff?from=1&to=2", admin());
        assertThat(diff.toString()).contains("sourceField");
        JsonNode rolled = post("/api/metrics/" + f.metricId() + "/rollback?version=1", Map.of(), admin(), 200);
        assertThat(rolled.get("sourceField").asText()).isEqualTo("amount - discount");
        assertThat(rolled.get("version").asInt()).isEqualTo(3);
    }

    @Test
    void wrongBaselineCannotBeVerified() throws Exception {
        Fixture f = ordersFixture();
        Map<String, Object> edit = Map.ofEntries(Map.entry("name", "Net revenue"), Map.entry("keyName", f.metricKey()), Map.entry("description", "x"), Map.entry("aggregation", "SUM"),
                Map.entry("sourceField", "amount"), Map.entry("active", true), Map.entry("tableId", f.tableId()), Map.entry("baselineValue", 675));
        call(put("/api/metrics/" + f.metricId()).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)), 200);
        post("/api/metrics/" + f.metricId() + "/approve", Map.of(), admin(), 200);
        post("/api/metrics/" + f.metricId() + "/verify", Map.of(), admin(), 400);
    }

    @Test
    void failedReplaceKeepsPriorGoodData() throws Exception {
        Fixture f = ordersFixture();
        JsonNode bad = upload(f.sourceId(), f.table(), "REPLACE", "order_id,region\nonly,two,extra\n", admin());
        assertThat(bad.get("status").asText()).isEqualTo("FAILED");
        JsonNode result = query(f, List.of(), admin());
        assertThat(result.get("rows").get(0).get(f.metricKey()).asDouble()).isEqualTo(675.0);
        JsonNode runs = get("/api/data-sources/" + f.sourceId() + "/runs", admin());
        assertThat(runs.get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(get("/api/data-sources", admin()).toString()).contains("\"lastRunStatus\":\"FAILED\"");
    }

    @Test
    void appendRejectsSchemaDriftAndPreviewReportsTypes() throws Exception {
        Fixture f = ordersFixture();
        JsonNode drift = upload(f.sourceId(), f.table(), "APPEND", "order_id,something_new\n9,x\n", admin());
        assertThat(drift.get("status").asText()).isEqualTo("FAILED");
        assertThat(drift.get("message").asText()).contains("Schema drift");
        JsonNode preview = call(multipart("/api/data-sources/csv/preview").file(new MockMultipartFile("file", "d.csv", "text/csv", ORDERS_CSV.getBytes(StandardCharsets.UTF_8))).with(admin()), 200);
        assertThat(preview.get("validRows").asInt()).isEqualTo(4);
        assertThat(preview.get("badRowCount").asInt()).isEqualTo(1);
        assertThat(preview.get("columns").toString()).contains("\"name\":\"order_date\",\"kind\":\"DATE\"");
    }

    @Test
    void askClarifiesInsteadOfGuessingAndSupportsFollowUps() throws Exception {
        Fixture f = ordersFixture();
        String conversation = post("/api/conversations", Map.of("title", "t"), admin(), 201).get("id").asText();
        JsonNode unknown = post("/api/conversations/" + conversation + "/questions", Map.of("question", "How many unicorns did we ship?"), admin(), 201);
        assertThat(unknown.get("clarification").asText()).contains("Net revenue " + f.suffix());
        assertThat(unknown.get("rows").size()).isZero();

        JsonNode a1 = post("/api/conversations/" + conversation + "/questions", Map.of("question", "What is net revenue " + f.suffix() + " by region?"), admin(), 201);
        assertThat(a1.get("rows").size()).isEqualTo(3);
        assertThat(a1.get("explanation").asText()).contains("by region");
        assertThat(a1.get("label").asText()).isEqualTo("VERIFIED");

        JsonNode a2 = post("/api/conversations/" + conversation + "/questions", Map.of("question", "what about by month"), admin(), 201);
        assertThat(a2.get("spec").get("metric").asText()).isEqualTo(f.metricKey());
        assertThat(a2.get("spec").get("dimensions").get(0).asText()).isEqualTo("time");
        assertThat(a2.get("rows").size()).isEqualTo(3);

        JsonNode badGroup = post("/api/conversations/" + conversation + "/questions", Map.of("question", "net revenue " + f.suffix() + " by shoe size"), admin(), 201);
        assertThat(badGroup.get("clarification").asText()).contains("can't group");

        // legacy one-shot endpoint uses the same governed path
        JsonNode oneShot = post("/api/ai/ask", Map.of("question", "net revenue " + f.suffix() + " for region is EMEA"), admin(), 200);
        assertThat(oneShot.get("rows").get(0).get(f.metricKey()).asDouble()).isEqualTo(290.0);
    }

    @Test
    void chatFollowUpsUseReturnedQueryAndRecheckViewerAndWorkspacePermissions() throws Exception {
        Fixture f = ordersFixture();
        JsonNode first = post("/api/ai/ask", Map.of("question", "net revenue " + f.suffix() + " by region", "mode", "chat"), admin(), 200);
        assertThat(first.path("query").path("metric").asText()).isEqualTo(f.metricKey());
        assertThat(first.path("executedAt").asText()).isNotBlank();
        assertThat(first.path("rows").size()).isEqualTo(3);
        JsonNode followUp = post("/api/ai/ask", Map.of("question", "what about by month", "mode", "chat", "previous", first.get("query")), admin(), 200);
        assertThat(followUp.path("query").path("metric").asText()).isEqualTo(f.metricKey());
        assertThat(followUp.path("query").path("dimensions").get(0).asText()).isEqualTo("time");
        assertThat(followUp.path("rows").size()).isEqualTo(3);

        RequestPostProcessor viewer = newUser(Role.VIEWER, "viewer-chat-test");
        post("/api/catalog/policies", Map.of("tableId", f.tableId(), "columnName", "region", "matchValue", "APAC", "role", "VIEWER"), admin(), 201);
        JsonNode restricted = post("/api/ai/ask", Map.of("question", "what about by month", "mode", "chat", "previous", first.get("query")), viewer, 200);
        assertThat(restricted.path("rows").size()).isEqualTo(1);
        assertThat(restricted.path("rows").get(0).path(f.metricKey()).asDouble()).isEqualTo(250.0);
        assertThat(restricted.path("warnings").toString()).contains("row-level access policy");
        assertThat(restricted.path("label").asText()).isEqualTo("VERIFIED");

        JsonNode clarification = post("/api/ai/ask", Map.of("question", "what about by shoe size", "mode", "chat", "previous", first.get("query")), viewer, 200);
        assertThat(clarification.path("clarification").asText()).contains("can't group");
        assertThat(clarification.hasNonNull("query")).isFalse();
        assertThat(clarification.hasNonNull("executedAt")).isFalse();
        assertThat(clarification.path("rows")).isEmpty();

        Workspace other = workspaces.save(new Workspace("Chat isolation", "chat-" + uid()));
        String outsiderName = "chat-" + uid() + "@test.local";
        users.save(new AppUser(other.getId(), outsiderName, outsiderName, encoder.encode("chat-isolation-test"), Role.ADMIN));
        JsonNode isolated = post("/api/ai/ask", Map.of("question", "what about by month", "mode", "chat", "previous", first.get("query")), httpBasic(outsiderName, "chat-isolation-test"), 200);
        assertThat(isolated.hasNonNull("query")).isFalse();
        assertThat(isolated.path("rows")).isEmpty();
        assertThat(isolated.path("clarification").asText()).contains("no longer available");
        post("/api/ai/ask", Map.of("question", "x".repeat(4001), "mode", "chat"), admin(), 400);
        post("/api/ai/ask", Map.of("question", " ", "mode", "chat"), admin(), 400);
        call(MockMvcRequestBuilders.post("/api/ai/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"net revenue\",\"mode\":\"chat\"}"), 401);
    }

    @Test
    void rowPoliciesColumnSensitivityAndTenantIsolation() throws Exception {
        Fixture f = ordersFixture();
        RequestPostProcessor viewer = newUser(Role.VIEWER, "viewer-pw-123");

        // row policy for every viewer: region = EMEA
        post("/api/catalog/policies", Map.of("tableId", f.tableId(), "columnName", "region", "matchValue", "EMEA", "role", "VIEWER"), admin(), 201);
        JsonNode viewerResult = query(f, List.of("region"), viewer);
        assertThat(viewerResult.get("rows").size()).isEqualTo(1);
        assertThat(viewerResult.get("rows").get(0).get("region").asText()).isEqualTo("EMEA");
        assertThat(viewerResult.get("warnings").toString()).contains("row-level access policy");
        assertThat(query(f, List.of("region"), admin()).get("rows").size()).isEqualTo(3);

        // viewers cannot manage anything
        post("/api/data-sources", Map.of("name", "x", "type", "CSV_UPLOAD"), viewer, 403);
        post("/api/metrics", Map.of("name", "x", "keyName", "x", "description", "x", "aggregation", "SUM", "sourceField", "amount"), viewer, 403);
        call(MockMvcRequestBuilders.get("/api/audit-events").with(viewer), 403);

        // restricted column: hidden from the catalogue and refused in queries for non-admins, still usable by admins
        call(put("/api/catalog/tables/" + f.tableId()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columns\":[{\"name\":\"cost\",\"sensitivity\":\"RESTRICTED\"}]}"), 200);
        JsonNode viewerTables = get("/api/catalog/tables", viewer);
        for (JsonNode t : viewerTables) if (t.get("id").asText().equals(f.tableId())) assertThat(t.get("columns").toString()).doesNotContain("\"cost\"");
        post("/api/query", Map.of("metric", f.metricKey(), "dimensions", List.of("cost")), viewer, 400);
        post("/api/query", Map.of("metric", f.metricKey(), "dimensions", List.of("no_such_column")), viewer, 400);
        post("/api/query", Map.of("metric", f.metricKey(), "dimensions", List.of("cost")), admin(), 200);

        // another workspace sees none of this
        Workspace other = workspaces.save(new Workspace("Other " + uid(), "other-" + uid()));
        String otherName = "other-" + uid() + "@test.local";
        users.save(new AppUser(other.getId(), otherName, otherName, encoder.encode("other-pw-123"), Role.ADMIN));
        RequestPostProcessor stranger = httpBasic(otherName, "other-pw-123");
        assertThat(get("/api/data-sources", stranger)).isEmpty();
        assertThat(get("/api/catalog/tables", stranger)).isEmpty();
        assertThat(get("/api/metrics", stranger)).isEmpty();
        post("/api/query", Map.of("metric", f.metricKey()), stranger, 404);
        call(MockMvcRequestBuilders.get("/api/data-sources/" + f.sourceId() + "/runs").with(stranger), 404);
        Map<String, Object> hijack = Map.of("name", "x", "keyName", "x", "description", "x", "aggregation", "SUM", "sourceField", "amount");
        call(put("/api/metrics/" + f.metricId()).with(stranger).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(hijack)), 404);
        call(MockMvcRequestBuilders.post("/api/data-sources/" + f.sourceId() + "/pause").with(stranger), 404);
        assertThat(get("/api/audit-events", stranger).toString()).doesNotContain(f.sourceId());
    }

    @Test
    void sharedReportRunsWithViewerPermissionsAndExports() throws Exception {
        Fixture f = ordersFixture();
        RequestPostProcessor viewer = newUser(Role.VIEWER, "viewer-pw-123");
        post("/api/catalog/policies", Map.of("tableId", f.tableId(), "columnName", "region", "matchValue", "APAC", "role", "VIEWER"), admin(), 201);

        Map<String, Object> request = Map.of("name", "Revenue by region " + f.suffix(), "visualization", "bar", "shared", true,
                "query", Map.of("metric", f.metricKey(), "dimensions", List.of("region")));
        JsonNode report = post("/api/reports", request, admin(), 201);
        String id = report.get("id").asText();

        JsonNode adminRun = get("/api/reports/" + id + "/run", admin());
        JsonNode viewerRun = get("/api/reports/" + id + "/run", viewer);
        assertThat(adminRun.get("rows").size()).isEqualTo(3);
        assertThat(viewerRun.get("rows").size()).isEqualTo(1);
        assertThat(viewerRun.get("rows").get(0).get("region").asText()).isEqualTo("APAC");

        String csv = mvc.perform(MockMvcRequestBuilders.get("/api/reports/" + id + "/export?format=csv").with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("region").contains("APAC").doesNotContain("EMEA").doesNotContain("AMER");
        byte[] pdf = mvc.perform(MockMvcRequestBuilders.get("/api/reports/" + id + "/export?format=pdf").with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        call(MockMvcRequestBuilders.get("/api/reports/" + id + "/export?format=xlsx").with(admin()), 400);

        // private reports are invisible to others
        Map<String, Object> priv = Map.of("name", "Private " + f.suffix(), "visualization", "bar", "shared", false, "query", Map.of("metric", f.metricKey()));
        String privateId = post("/api/reports", priv, admin(), 201).get("id").asText();
        call(MockMvcRequestBuilders.get("/api/reports/" + privateId + "/run").with(viewer), 404);
        assertThat(get("/api/reports", viewer).toString()).doesNotContain(privateId);
        assertThat(get("/api/audit-events", admin()).toString()).contains("EXPORT");
    }

    @Test
    void templatesAndPublishedTemplatesUseLiveDataAndViewerPolicies() throws Exception {
        Fixture f = ordersFixture();
        RequestPostProcessor viewer = newUser(Role.VIEWER, "template-viewer-pw");
        post("/api/catalog/policies", Map.of("tableId", f.tableId(), "columnName", "region", "matchValue", "APAC", "role", "VIEWER"), admin(), 201);
        String reportId = post("/api/reports", Map.of("name", "Template source " + f.suffix(), "visualization", "bar", "shared", true,
                "query", Map.of("metric", f.metricKey(), "dimensions", List.of("region"))), admin(), 201).get("id").asText();
        Map<String, Object> widget = Map.of("id", "revenue", "title", "Regional revenue", "sourceType", "report", "sourceId", reportId,
                "chart", "bar", "labelKey", "region", "valueKey", f.metricKey(), "columns", List.of("region", f.metricKey()), "width", "full");
        Map<String, Object> definition = Map.of("version", 1, "name", "Live revenue template", "description", "Viewer scoped revenue", "widgets", List.of(widget));
        String templateId = post("/api/report-templates", definition, admin(), 201).get("id").asText();
        assertThat(get("/api/report-templates/" + templateId + "/run", admin()).at("/widgets/0/rows").size()).isEqualTo(3);
        JsonNode viewerResult = get("/api/report-templates/" + templateId + "/run", viewer).at("/widgets/0");
        assertThat(viewerResult.get("rows").size()).isEqualTo(1);
        assertThat(viewerResult.at("/rows/0/region").asText()).isEqualTo("APAC");
        assertThat(viewerResult.get("sample").asBoolean()).isFalse();
        assertThat(viewerResult.get("warnings").toString()).contains("row-level access policy");
        String token = post("/api/published-dashboards", Map.of("name", "Live published template", "kind", "template", "reportId", templateId), admin(), 201).get("token").asText();
        String publishedData = "/api/published-dashboards/token/" + token + "/data";
        assertThat(get(publishedData, viewer).at("/widgets/0/rows").size()).isEqualTo(1);
        upload(f.sourceId(), f.table(), "APPEND", "order_id,region,order_date,amount,discount,cost,customer_id\n99,APAC,2026-03-05,50,0,10,c9\n", admin());
        assertThat(get(publishedData, viewer).at("/widgets/0/rows/0/" + f.metricKey()).asDouble()).isEqualTo(300.0);
        call(put("/api/reports/" + reportId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Now private", "visualization", "bar", "shared", false,
                        "query", Map.of("metric", f.metricKey(), "dimensions", List.of("region"))))), 200);
        call(MockMvcRequestBuilders.get(publishedData).with(viewer), 404);
    }

    @Test
    void workspaceBudgetStopsQueries() throws Exception {
        Workspace ws = workspaces.save(new Workspace("Budget " + uid(), "budget-" + uid()));
        String adminName = "budget-" + uid() + "@test.local";
        users.save(new AppUser(ws.getId(), adminName, adminName, encoder.encode("budget-pw-123"), Role.ADMIN));
        RequestPostProcessor budgetAdmin = httpBasic(adminName, "budget-pw-123");
        call(put("/api/workspace/limits").with(budgetAdmin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxQueriesPerDay\":1,\"maxRows\":5,\"queryTimeoutSeconds\":5,\"staleAfterMinutes\":60}"), 200);
        String sourceId = post("/api/data-sources", Map.of("name", "b", "type", "CSV_UPLOAD"), budgetAdmin, 201).get("id").asText();
        upload(sourceId, "t_" + uid(), "REPLACE", "k,v\n=SUM(A1),1\nb,2\n", budgetAdmin);
        String tableId = get("/api/catalog/tables", budgetAdmin).get(0).get("id").asText();
        String key = "cnt_" + uid();
        String metricId = post("/api/metrics", Map.of("name", "Count", "keyName", key, "description", "rows", "aggregation", "COUNT", "sourceField", "*", "active", true, "tableId", tableId), budgetAdmin, 201).get("id").asText();
        post("/api/metrics/" + metricId + "/approve", Map.of(), budgetAdmin, 200);
        post("/api/query", Map.of("metric", key, "dimensions", List.of("k")), budgetAdmin, 200);
        post("/api/query", Map.of("metric", key), budgetAdmin, 429);
        assertThat(get("/api/workspace", budgetAdmin).get("queriesToday").asInt()).isEqualTo(1);
    }

    @Test
    void restConnectorLoadsInitialThenIncrementalWithoutDuplicates() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> sinceSeen = new ArrayList<>();
        List<String[]> all = new ArrayList<>(List.of(new String[]{"1", "a", "2026-01-01"}, new String[]{"2", "b", "2026-01-02"}, new String[]{"3", "c", "2026-01-03"}));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/items", ex -> {
            String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            if (calls.getAndIncrement() == 0) { ex.getResponseHeaders().add("Retry-After", "0"); ex.sendResponseHeaders(429, -1); ex.close(); return; }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String since = query.contains("since=") ? query.replaceAll(".*since=([^&]*).*", "$1") : "";
            sinceSeen.add(since);
            int page = query.contains("&page=") || query.startsWith("page=") ? Integer.parseInt(query.replaceAll(".*(?:^|&)page=(\\d+).*", "$1")) : 1;
            List<String[]> filtered = all.stream().filter(r -> since.isEmpty() || r[2].compareTo(since) > 0).toList();
            StringBuilder items = new StringBuilder();
            for (String[] r : filtered.subList(Math.min((page - 1) * 2, filtered.size()), Math.min(page * 2, filtered.size()))) {
                if (items.length() > 0) items.append(',');
                items.append("{\"id\":").append(r[0]).append(",\"name\":\"").append(r[1]).append("\",\"updated\":\"").append(r[2]).append("\"}");
            }
            byte[] body = ("{\"data\":[" + items + "]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (!"Bearer rest-token".equals(auth)) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/items";
            String table = "items_" + uid();
            Map<String, Object> config = Map.of("url", url, "authType", "BEARER", "recordsPath", "/data", "paginationType", "PAGE", "pageSize", 2,
                    "incrementalParam", "since", "incrementalField", "updated", "primaryKey", "id", "table", table);
            String sourceId = post("/api/data-sources", Map.of("name", "rest-" + uid(), "type", "REST_API", "secret", "rest-token", "config", config, "scheduleMinutes", 60), admin(), 201).get("id").asText();
            JsonNode created = get("/api/data-sources", admin());
            assertThat(created.toString()).doesNotContain("rest-token").contains("\"hasSecret\":true");

            JsonNode first = post("/api/data-sources/" + sourceId + "/run", Map.of(), admin(), 200);
            assertThat(first.get("status").asText()).as(first.toString()).isEqualTo("SUCCESS");
            assertThat(first.get("rowsLoaded").asInt()).isEqualTo(3);
            // simulate a changed record 3 plus a new record 4 arriving later
            all.set(2, new String[]{"3", "c2", "2026-01-04"});
            all.add(new String[]{"4", "d", "2026-01-05"});
            JsonNode second = post("/api/data-sources/" + sourceId + "/run", Map.of(), admin(), 200);
            assertThat(second.get("status").asText()).as(second.toString()).isEqualTo("SUCCESS");
            assertThat(sinceSeen).contains("2026-01-03");
            assertThat(second.get("rowsLoaded").asInt()).isEqualTo(2);

            String tableId = null;
            for (JsonNode t : get("/api/catalog/tables", admin())) if (t.get("name").asText().equals(table)) { tableId = t.get("id").asText(); assertThat(t.get("rowCount").asInt()).isEqualTo(4); }
            assertThat(tableId).isNotNull();
            assertThat(get("/api/data-sources/" + sourceId + "/runs", admin()).size()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test
    void dumpImportsOnceAndRejectsUnexpectedFiles() throws Exception {
        String[] content = {"sku,qty\nA,1\nB,2\n"};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dump.csv", ex -> {
            byte[] body = content[0].getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/dump.csv";
            String table = "dump_" + uid();
            String sourceId = post("/api/data-sources", Map.of("name", "dump-" + uid(), "type", "FILE_DUMP", "config", Map.of("url", url, "table", table)), admin(), 201).get("id").asText();
            assertThat(post("/api/data-sources/" + sourceId + "/run", Map.of(), admin(), 200).get("status").asText()).isEqualTo("SUCCESS");
            assertThat(post("/api/data-sources/" + sourceId + "/run", Map.of(), admin(), 200).get("status").asText()).isEqualTo("SKIPPED");

            content[0] = "sku,qty\nA,1\nB\n";
            JsonNode malformed = post("/api/data-sources/" + sourceId + "/run", Map.of(), admin(), 200);
            assertThat(malformed.get("status").asText()).isEqualTo("FAILED");
            assertThat(malformed.get("message").asText()).contains("malformed");
            content[0] = "sku,qty\nA\0,1\n";
            assertThat(post("/api/data-sources/" + sourceId + "/run", Map.of(), admin(), 200).get("message").asText()).contains("not a text CSV");

            for (JsonNode t : get("/api/catalog/tables", admin())) if (t.get("name").asText().equals(table)) assertThat(t.get("rowCount").asInt()).isEqualTo(2);

            String pinned = post("/api/data-sources", Map.of("name", "pin-" + uid(), "type", "FILE_DUMP", "config", Map.of("url", url, "table", "pin_" + uid(), "expectedSha256", "0".repeat(64))), admin(), 201).get("id").asText();
            content[0] = "sku,qty\nA,1\n";
            assertThat(post("/api/data-sources/" + pinned + "/run", Map.of(), admin(), 200).get("message").asText()).contains("Checksum mismatch");
        } finally { server.stop(0); }
    }

    @Test
    void outboundUrlsMustBeAllowlistedAndSecretsStayWriteOnly() throws Exception {
        post("/api/data-sources", Map.of("name", "evil", "type", "FILE_DUMP", "config", Map.of("url", "http://evil.example.com/x.csv", "table", "t")), admin(), 400);
        post("/api/data-sources", Map.of("name", "evil2", "type", "REST_API", "config", Map.of("url", "http://169.254.169.254/latest", "table", "t")), admin(), 400);
        post("/api/data-sources", Map.of("name", "leak", "type", "REST_API", "config", Map.of("url", "http://localhost:1/x", "table", "t", "apiKey", "oops")), admin(), 400);
        JsonNode created = post("/api/data-sources", Map.of("name", "pw-" + uid(), "type", "POSTGRESQL", "host", "db.internal", "port", 5432, "databaseName", "sales",
                "username", "ro", "secret", "s3cret-pw", "config", Map.of("allowedTables", List.of("public.orders"))), admin(), 201);
        assertThat(created.toString()).doesNotContain("s3cret-pw").contains("\"hasSecret\":true");
        assertThat(get("/api/data-sources", admin()).toString()).doesNotContain("s3cret-pw");
        post("/api/data-sources", Map.of("name", "inj", "type", "POSTGRESQL", "host", "db;evil=1", "port", 5432, "databaseName", "x", "username", "u", "secret", "p"), admin(), 400);
        post("/api/data-sources/" + created.get("id").asText() + "/rotate-secret", Map.of("secret", "new-secret-value"), admin(), 200);
        assertThat(get("/api/audit-events", admin()).toString()).contains("ROTATE_SECRET").doesNotContain("s3cret-pw").doesNotContain("new-secret-value");
    }

    @Test
    void databaseSourceExposesOnlyAllowedTablesAndHonoursPause() throws Exception {
        try (Connection c = DriverManager.getConnection(REMOTE_URL); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS PUBLIC.SALES_X (ID INT, REGION VARCHAR(20), TOTAL DECIMAL(10,2))");
            st.execute("DELETE FROM PUBLIC.SALES_X");
            st.execute("INSERT INTO PUBLIC.SALES_X VALUES (1,'N',10.5),(2,'N',4.5),(3,'S',5)");
            st.execute("CREATE TABLE IF NOT EXISTS PUBLIC.SECRETS_X (ID INT, V VARCHAR(20))");
        }
        String s = uid();
        String sourceId = post("/api/data-sources", Map.of("name", "pg-" + s, "type", "POSTGRESQL", "host", "db.internal", "port", 5432, "databaseName", "sales", "username", "ro",
                "secret", "s3cret-pw", "config", Map.of("allowedTables", List.of("PUBLIC.SALES_X"))), admin(), 201).get("id").asText();
        assertThat(post("/api/data-sources/" + sourceId + "/test", Map.of(), admin(), 200).get("ok").asBoolean()).isTrue();
        post("/api/catalog/sources/" + sourceId + "/tables", Map.of("table", "PUBLIC.SECRETS_X"), admin(), 400);
        JsonNode table = post("/api/catalog/sources/" + sourceId + "/tables", Map.of("table", "PUBLIC.SALES_X"), admin(), 201);
        String tableId = table.get("id").asText();
        assertThat(table.get("columns").toString()).contains("\"name\":\"REGION\"");

        String key = "tot_" + s;
        String metricId = post("/api/metrics", Map.of("name", "Total " + s, "keyName", key, "description", "t", "aggregation", "SUM", "sourceField", "TOTAL", "active", true, "tableId", tableId), admin(), 201).get("id").asText();
        post("/api/metrics/" + metricId + "/approve", Map.of(), admin(), 200);
        JsonNode result = post("/api/query", Map.of("metric", key, "dimensions", List.of("REGION")), admin(), 200);
        Map<String, Double> byRegion = new java.util.HashMap<>();
        for (JsonNode r : result.get("rows")) byRegion.put(r.get("REGION").asText(), r.get(key).asDouble());
        assertThat(byRegion).containsEntry("N", 15.0).containsEntry("S", 5.0);

        post("/api/data-sources/" + sourceId + "/pause", Map.of(), admin(), 200);
        post("/api/query", Map.of("metric", key), admin(), 400);
        post("/api/data-sources/" + sourceId + "/resume", Map.of(), admin(), 200);
        post("/api/query", Map.of("metric", key), admin(), 200);

        post("/api/data-sources/" + sourceId + "/rotate-secret", Map.of("secret", "wrong"), admin(), 200);
        assertThat(post("/api/data-sources/" + sourceId + "/test", Map.of(), admin(), 200).get("ok").asBoolean()).isFalse();
    }

    @Test
    void relationshipTestBlocksFanOutJoinsAndAllowsSafeOnes() throws Exception {
        Fixture f = ordersFixture();
        String customers = "customers_" + uid();
        JsonNode run = upload(f.sourceId(), customers, "REPLACE", "customer_id,country\nc1,DE\nc2,FR\nc3,US\n", admin());
        assertThat(run.get("status").asText()).isEqualTo("SUCCESS");
        String dupes = "dupes_" + uid();
        upload(f.sourceId(), dupes, "REPLACE", "customer_id,tier\nc1,gold\nc1,silver\nc2,bronze\nc3,bronze\n", admin());
        String customersId = null, dupesId = null;
        for (JsonNode t : get("/api/catalog/tables", admin())) {
            if (t.get("name").asText().equals(customers)) customersId = t.get("id").asText();
            if (t.get("name").asText().equals(dupes)) dupesId = t.get("id").asText();
        }
        JsonNode unsafe = post("/api/catalog/relationships", Map.of("fromTableId", f.tableId(), "fromColumn", "customer_id", "toTableId", dupesId, "toColumn", "customer_id", "cardinality", "MANY_TO_ONE"), admin(), 201);
        assertThat(unsafe.get("testStatus").asText()).isEqualTo("UNSAFE");
        assertThat(unsafe.get("testMessage").asText()).contains("duplicate");
        post("/api/query", Map.of("metric", f.metricKey(), "dimensions", List.of(dupes + ".tier")), admin(), 400);

        JsonNode safe = post("/api/catalog/relationships", Map.of("fromTableId", f.tableId(), "fromColumn", "customer_id", "toTableId", customersId, "toColumn", "customer_id", "cardinality", "MANY_TO_ONE"), admin(), 201);
        assertThat(safe.get("testStatus").asText()).isEqualTo("SAFE");
        JsonNode joined = post("/api/query", Map.of("metric", f.metricKey(), "dimensions", List.of(customers + ".country")), admin(), 200);
        Set<String> countries = new HashSet<>();
        double total = 0;
        for (JsonNode r : joined.get("rows")) { countries.add(r.get(customers + ".country").asText()); total += r.get(f.metricKey()).asDouble(); }
        assertThat(countries).containsExactlyInAnyOrder("DE", "FR", "US");
        assertThat(total).isEqualTo(675.0);
        assertThat(joined.get("lineage").get("tables").size()).isEqualTo(2);
    }

    @Test
    void metricsCannotReferenceUnknownColumnsOrInjectSql() throws Exception {
        Fixture f = ordersFixture();
        Map<String, Object> base = Map.ofEntries(Map.entry("name", "x"), Map.entry("keyName", "bad_" + uid()), Map.entry("description", "x"), Map.entry("aggregation", "SUM"),
                Map.entry("active", true), Map.entry("tableId", f.tableId()));
        for (String field : List.of("amount; DROP TABLE x", "nope", "region", "amount - (select 1)")) {
            Map<String, Object> m = new java.util.HashMap<>(base);
            m.put("sourceField", field);
            post("/api/metrics", m, admin(), 400);
        }
        JsonNode injected = post("/api/query", Map.of("metric", f.metricKey(), "filters", List.of(Map.of("column", "region", "op", "EQ", "values", List.of("EMEA' OR '1'='1")))), admin(), 200);
        JsonNode cell = injected.get("rows").get(0).get(f.metricKey());
        assertThat(cell == null || cell.isNull()).isTrue();
        post("/api/query", Map.of("metric", f.metricKey(), "dimensions", List.of("region; DROP TABLE x")), admin(), 400);
        post("/api/query", Map.of("metric", f.metricKey(), "sortBy", "amount"), admin(), 400);
    }

    @Test
    void authenticationAndAdminAuditTrail() throws Exception {
        call(MockMvcRequestBuilders.get("/api/data-sources"), 401);
        call(MockMvcRequestBuilders.get("/api/query/options"), 401);
        Fixture f = ordersFixture();
        JsonNode audit = get("/api/audit-events", admin());
        assertThat(audit.toString()).contains("INGEST").contains("APPROVE").contains("VERIFY").contains("QUERY");
        assertThat(get("/api/query/options", admin()).toString()).contains(f.metricKey());
    }
}
