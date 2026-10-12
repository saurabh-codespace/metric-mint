package com.toscana_insights.toscana;

import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Workspace;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class I2pInvoiceLookupTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser current = mock(CurrentUser.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final I2pInvoiceLookup lookup = new I2pInvoiceLookup(jdbc, current, workspaces);
    private final UUID workspaceId = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {
            "Can you pull the latest invoice number which is in duplicate queue and assigned to whom?",
            "Show the latest invoice in the duplicate queue.",
            "Please fetch the newest invoice in duplicate queue and its assignee!",
            "Get the most recent invoice in duplicate queue"
    })
    void answersLatestDuplicateInvoiceAndCurrentAssigneeFromReturnedRow(String question) {
        permitDefaultAdmin();
        Map<String, Object> row = Map.of("invoiceNumber", "INV-9001", "workItemNumber", "WI-42", "queue", "Duplicate Identifier",
                "assignedTo", "sample.operator", "createdAt", "2026-10-12T11:30:00", "invoiceDate", "2026-10-01");
        when(jdbc.queryForList(anyString(), eq(1), eq("Duplicate Check"), eq("Duplicate Identifier"))).thenReturn(List.of(row));

        var result = lookup.answer(question);

        assertThat(I2pInvoiceLookup.isDetailQuestion(question)).isTrue();
        assertThat(result.answer()).contains("INV-9001", "WI-42", "sample.operator", "Duplicate Identifier");
        assertThat(result.rows()).isEqualTo(List.of(row));
        assertThat(result.visualization()).isEqualTo("table");
        assertThat(result.clarification()).isNull();
        assertThat(result.explanation()).contains("CreatedDate descending", "RowID descending", "does not mean invoice date or queue-entry time",
                "no timezone offset", "I2P LOB 92");
        assertThat(result.lineage().metricVerified()).isFalse();
        assertThat(result.lineage().metricStatus()).isEqualTo("OPERATIONAL");
        assertThat(result.lineage().parameters()).containsExactly("1", "Duplicate Check", "Duplicate Identifier");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(1), eq("Duplicate Check"), eq("Duplicate Identifier"));
        assertThat(sql.getValue()).contains("SELECT TOP (1)", "[Toscana_I2P-SIT].dbo.NeonExternal", "e.lobID=92",
                "LEFT JOIN [Toscana_I2P-SIT].dbo.TOSCANA3_USER", "u.UserIndex=e.assignTo",
                "(?=0 OR e.currQueue IN (?,?))", "ORDER BY e.CreatedDate DESC,e.RowID DESC",
                "'Unassigned'", "CONCAT('User ID ',e.assignTo)");
        assertThat(sql.getValue()).doesNotContain(question, "dashboard_sample_invoices", "ORDER BY e.InvoiceDate");
        assertThat(result.lineage().sql()).isEqualTo(sql.getValue());
    }

    @ParameterizedTest
    @CsvSource({
            "Show the latest invoice in Duplicate Check queue, 1, Duplicate Check, Duplicate Check",
            "Show the latest invoice in Duplicate Identifier queue, 1, Duplicate Identifier, Duplicate Identifier",
            "Show the latest invoice in Duplicate Check and Duplicate Identifier queue, 1, Duplicate Check, Duplicate Identifier",
            "Show the latest invoice and its assignee, 0, Duplicate Check, Duplicate Identifier"
    })
    void selectsExplicitQueuesUsingBoundParameters(String question, int filter, String first, String second) {
        permitDefaultAdmin();
        when(jdbc.queryForList(anyString(), eq(filter), eq(first), eq(second))).thenReturn(List.of());
        var result = lookup.answer(question);
        verify(jdbc).queryForList(anyString(), eq(filter), eq(first), eq(second));
        assertThat(result.lineage().parameters()).containsExactly(Integer.toString(filter), first, second);
        assertThat(result.rows()).isEqualTo(List.of());
        assertThat(result.answer()).contains("No I2P invoice currently matches");
        assertThat(result.clarification()).isNull();
        assertThat(result.answer()).doesNotContain("assigned to", "null");
        if (filter == 0) assertThat(result.explanation()).contains("all I2P queues");
        else if (first.equals(second)) assertThat(result.explanation()).contains("Current queue: " + first + ".");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Show the latest invoice in duplicate queue before 2026-10-01",
            "Show the latest invoice in duplicate queue today",
            "Show the latest paid invoice",
            "Show the latest invoice not in duplicate queue",
            "Show the latest invoice in duplicate queue; DROP TABLE NeonExternal",
            "Show the latest invoice in duplicate queue ' OR 1=1 --",
            "Show invoice INV-9001",
            "Who is the invoice assigned to?",
            "Show the duplicate queue",
            ""
    })
    void unsupportedRequestsAndInjectionNeverReachJdbc(String question) {
        var result = lookup.answer(question);
        assertThat(result.clarification()).isNotBlank();
        assertThat(result.lineage()).isNull();
        verifyNoInteractions(jdbc, current, workspaces);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Show the latest invoice in the queue", "Show the latest invoice in check", "Show the latest invoice in identifier"})
    void ambiguousQueueRequestsClarifyWithoutJdbc(String question) {
        permitDefaultAdmin();
        var result = lookup.answer(question);
        assertThat(result.clarification()).contains("Duplicate Check", "Duplicate Identifier");
        assertThat(result.lineage()).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test
    void nonAdministratorsCannotQueryOperationalDetails() {
        when(current.isAdmin()).thenReturn(false);
        assertThatThrownBy(() -> lookup.answer("Show the latest invoice in duplicate queue"))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(jdbc, workspaces);
    }

    @Test
    void administratorsInAnotherOrMissingWorkspaceCannotQueryOperationalDetails() {
        when(current.isAdmin()).thenReturn(true);
        when(current.workspaceId()).thenReturn(workspaceId);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(new Workspace("Other", "other")), Optional.empty());
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> lookup.answer("Show the latest invoice in duplicate queue"))
                    .isInstanceOf(ForbiddenException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void doesNotInventMissingInvoiceNumberOrAssignee() {
        permitDefaultAdmin();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("invoiceNumber", null);
        row.put("workItemNumber", "WI-9");
        row.put("queue", "Duplicate Check");
        row.put("assignedTo", "Unassigned");
        when(jdbc.queryForList(anyString(), eq(1), anyString(), anyString())).thenReturn(List.of(row));
        var result = lookup.answer("Show the latest invoice in duplicate queue");
        assertThat(result.answer()).contains("missing its invoice number", "WI-9", "assigned to Unassigned").doesNotContain("null");
    }

    @Test
    void routesDetailQuestionsWithoutHijackingAggregateInvoiceQuestions() {
        assertThat(I2pInvoiceLookup.isDetailQuestion("latest invoice number")).isTrue();
        assertThat(I2pInvoiceLookup.isDetailQuestion("who is the invoice assigned to?")).isTrue();
        assertThat(I2pInvoiceLookup.isDetailQuestion("duplicate queue")).isTrue();
        assertThat(I2pInvoiceLookup.isDetailQuestion("invoice performance")).isFalse();
        assertThat(I2pInvoiceLookup.isDetailQuestion("monthly invoice volume")).isFalse();
        assertThat(I2pInvoiceLookup.isDetailQuestion("invoice count last month")).isFalse();
        assertThat(I2pInvoiceLookup.isDetailQuestion("recent invoice volume")).isFalse();
        assertThat(I2pInvoiceLookup.isDetailQuestion("revenue by region")).isFalse();
    }

    private void permitDefaultAdmin() {
        when(current.isAdmin()).thenReturn(true);
        when(current.workspaceId()).thenReturn(workspaceId);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(new Workspace("Default", "default")));
    }
}
