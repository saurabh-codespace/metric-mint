package com.toscana_insights.toscana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings("unchecked")
class ToscanaAnalyticsAnswerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ToscanaAnalyticsService service = new ToscanaAnalyticsService(jdbc);

    @ParameterizedTest
    @ValueSource(strings = {
            "Show the latest invoice in the duplicate queue", "Which queue has the most invoices?", "Show queue workload by region",
            "Show invoice details", "Show latest invoice", "Show invoices for vendor ABC", "Invoice performance",
            "How many unicorns?", "Show monthly invoices in India", "Show monthly invoice volume in 2025",
            "Show monthly invoice volume for AP", "Show monthly invoice volume last month", "Show invoice status distribution today",
            "Show open invoices over 30 days old", "Show open invoices by region", "Show regional workload in India",
            "Show monthly invoice volume and status", "Show duplicate invoice status", "Show regional workload excluding India",
            "Show invoice status distribution for LOB 92", "Show invoice status distribution where status is open",
            "Show invoice amount by month", "Show paid invoices by month", "Show invoice volume by day", "Show status for invoice 123"
    })
    void unsupportedQueueDetailOrFilteredRequestsClarifyWithoutQuerying(String question) {
        ToscanaAnalyticsService.Answer answer = service.answer(question);
        assertThat(answer.clarification()).isNotBlank();
        assertThat(answer.rows()).isEqualTo(List.of());
        assertThat(answer.answer()).doesNotContain("largest regional", "has 0 open");
        verifyNoInteractions(jdbc);
    }

    @Test
    void monthlyStarterOnlyQueriesCreationMonthsAndTotalsReturnedMonths() {
        List<ToscanaAnalyticsService.PeriodCount> rows = List.of(new ToscanaAnalyticsService.PeriodCount("2026-09", 40), new ToscanaAnalyticsService.PeriodCount("2026-10", 50));
        doReturn(rows).when(jdbc).query(anyString(), any(RowMapper.class));
        ToscanaAnalyticsService.Answer answer = service.answer("Show monthly invoice volume");
        assertThat(answer.rows()).isEqualTo(rows);
        assertThat(answer.answer()).contains("LOB 90", "90 work items", "displayed creation months");
        assertThat(answer.explanation()).contains("Latest 12", "AccountsPayable_External");
        assertThat(answer.clarification()).isNull();
        verify(jdbc).query(argThat((String sql) -> sql.contains("CreatedDate") && sql.contains("lobId=90") && sql.contains("GROUP BY")), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void statusStarterOnlyQueriesApStatuses() {
        List<ToscanaAnalyticsService.CategoryCount> rows = List.of(new ToscanaAnalyticsService.CategoryCount("Completed", 60));
        doReturn(rows).when(jdbc).query(anyString(), any(RowMapper.class));
        ToscanaAnalyticsService.Answer answer = service.answer("Show invoice status distribution");
        assertThat(answer.rows()).isEqualTo(rows);
        assertThat(answer.answer()).contains("LOB 90", "status distribution");
        assertThat(answer.clarification()).isNull();
        verify(jdbc).query(argThat((String sql) -> sql.contains("TOSCANA3_STATUSMASTER") && sql.contains("e.lobId=90")), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void regionalSummaryIsExplicitlyApAndNeverClaimsQueueData() {
        List<ToscanaAnalyticsService.CategoryCount> rows = List.of(new ToscanaAnalyticsService.CategoryCount("EMEA", 42));
        doReturn(rows).when(jdbc).query(anyString(), any(RowMapper.class));
        ToscanaAnalyticsService.Answer answer = service.answer("Show AP workload by region");
        assertThat(answer.rows()).isEqualTo(rows);
        assertThat(answer.answer()).contains("LOB 90", "EMEA", "42").doesNotContain("queue");
        assertThat(answer.explanation()).contains("not queue data", "all statuses");
        assertThat(answer.clarification()).isNull();
        verify(jdbc).query(argThat((String sql) -> sql.contains("Region") && sql.contains("lobId=90") && sql.contains("GROUP BY")), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Which LOB has the most open invoices?", "Show open AP invoices", "Show AP open invoices", "How many open AP invoices are there?"})
    void openStarterReportsOnlyApWithHonestLobScope(String question) {
        List<ToscanaAnalyticsService.LobSummary> rows = List.of(new ToscanaAnalyticsService.LobSummary("Accounts Payable (LOB 90)", 100, 70, 30, 4));
        doReturn(rows).when(jdbc).query(anyString(), any(RowMapper.class));
        ToscanaAnalyticsService.Answer answer = service.answer(question);
        assertThat(answer.rows()).isEqualTo(rows);
        assertThat(answer.answer()).contains("LOB 90", "30 open work items");
        assertThat(answer.explanation()).contains("Completed_Date is null", "LOB 90 only");
        if (question.startsWith("Which LOB")) assertThat(answer.answer()).contains("cannot rank all I2P LOBs");
        assertThat(answer.clarification()).isNull();
        verify(jdbc).query(argThat((String sql) -> sql.contains("lobId=90") && sql.contains("Completed_Date")), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }
}
