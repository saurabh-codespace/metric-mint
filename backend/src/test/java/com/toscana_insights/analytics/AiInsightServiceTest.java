package com.toscana_insights.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiInsightServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createsInsightsFromGovernedRows() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String insight = json.writeValueAsString(Map.of("headline", "Open work needs attention", "summary", "Two LOBs were compared.",
                    "insights", List.of("Finance has the highest open count."), "recommendedChart", "pie"));
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", insight))))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiInsightService service = new AiInsightService(json, "openai", "test-key", "test-model", "http://localhost:" + server.getAddress().getPort());
            AiInsightService.Insight result = service.dashboard("Build a dashboard", "Fallback", "Notes",
                    List.of(Map.of("lob", "Finance", "open", 12)), "bar");

            assertThat(result.aiGenerated()).isTrue();
            assertThat(result.visualization()).isEqualTo("pie");
            assertThat(result.insights()).containsExactly("Finance has the highest open count.");
            assertThat(requestBody.get()).contains("test-model", "governedRows", "Finance");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsTheGovernedFallbackWhenAiIsDisabled() {
        AiInsightService service = new AiInsightService(json, "disabled", "", "", "http://localhost");
        AiInsightService.Insight result = service.dashboard("Dashboard", "Calculated answer", "Calculated notes",
                List.of(Map.of("value", 10)), "bar");

        assertThat(result.aiGenerated()).isFalse();
        assertThat(result.answer()).isEqualTo("Calculated answer");
        assertThat(result.insights()).isEmpty();
    }
}
