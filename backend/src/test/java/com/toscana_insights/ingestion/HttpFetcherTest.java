package com.toscana_insights.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.query.QuerySpec;
import com.toscana_insights.report.ReportExporter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpFetcherTest {
    private final HttpFetcher strict = new HttpFetcher("localhost,api.example.com", false, 1);

    @Test
    void refusesInternalAddressesEvenWhenHostIsAllowlisted() {
        assertThatThrownBy(() -> strict.validate("https://localhost/data")).isInstanceOf(BadRequestException.class).hasMessageContaining("private or internal");
    }

    @Test
    void refusesPlainHttpUnknownHostsAndEmbeddedCredentials() {
        assertThatThrownBy(() -> strict.validate("http://api.example.com/x")).hasMessageContaining("https");
        assertThatThrownBy(() -> strict.validate("https://169.254.169.254/latest/meta-data")).hasMessageContaining("allowed host list");
        assertThatThrownBy(() -> strict.validate("https://user:pw@api.example.com/x")).hasMessageContaining("Credentials");
    }

    @Test
    void csvExportNeutralisesSpreadsheetFormulas() {
        QueryService.Result result = new QueryService.Result("m", "M", List.of("name", "m"), List.of(Map.of("name", "=HYPERLINK(\"x\")", "m", -5), Map.of("name", "+cmd", "m", 3)),
                false, "EXPLORATORY", List.of(), null, Instant.now(), new QuerySpec("m", null, null, null, null, null, null, null));
        String csv = new String(new ReportExporter().csv(result), StandardCharsets.UTF_8);
        assertThat(csv).contains("'=HYPERLINK").contains("'+cmd").contains(",-5");
    }
}
