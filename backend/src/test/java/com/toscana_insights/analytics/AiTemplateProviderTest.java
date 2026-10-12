package com.toscana_insights.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiTemplateProviderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsStrictMetadataOnlySchemaAndRejectsRefusalIncompleteAndUnexpectedFields() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<Map<String, Object>> response = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = json.writeValueAsBytes(response.get());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiInsightService service = new AiInsightService(json, "openai", "test-placeholder", "test-model", "http://localhost:" + server.getAddress().getPort());
            String definition = "{\"definition\":{\"version\":1,\"name\":\"Status\",\"description\":\"AP status\",\"widgets\":[{\"id\":\"a\",\"title\":\"Status\",\"sourceType\":\"connected\",\"sourceId\":\"invoice-status\",\"chart\":\"bar\",\"labelKey\":\"name\",\"valueKey\":\"count\",\"columns\":[],\"width\":\"half\"}]},\"clarification\":null}";
            Map<String, Object> text = Map.of("type", "output_text", "text", definition);
            response.set(Map.of("status", "completed", "output", List.of(Map.of("content", List.of(text)))));
            var plan = service.generateTemplate("Create invoice status dashboard", "live", List.of(Map.of("id", "invoice-status", "fields", List.of("name", "count"))));
            assertThat(plan.definition().widgets()).hasSize(1);
            var body = json.readTree(request.get());
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(body.path("input").asText()).contains("invoice-status", "fields").doesNotContain("rows", "Authorization", "test-placeholder");
            for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                    Map.of("status", "incomplete", "output", List.of(Map.of("content", List.of(text)))),
                    Map.of("status", "completed", "output", List.of(Map.of("content", List.of(text, Map.of("type", "refusal", "refusal", "No"))))),
                    Map.of("status", "completed", "output", List.of(Map.of("content", List.of(text, text)))),
                    Map.of("status", "completed", "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", definition.replace("\"version\":1", "\"sql\":\"DROP TABLE x\",\"version\":1")))))))) {
                response.set(invalid);
                assertThatThrownBy(() -> service.generateTemplate("Create status dashboard", "live", List.of())).isInstanceOf(IllegalStateException.class);
            }
        } finally { server.stop(0); }
    }
}
