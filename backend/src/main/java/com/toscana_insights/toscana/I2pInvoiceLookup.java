package com.toscana_insights.toscana;

import com.toscana_insights.common.ForbiddenException;
import com.toscana_insights.query.QueryService;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded invoice lookup against the installed I2P source; user text is never executable SQL. */
@Service
public class I2pInvoiceLookup {
    private static final Set<String> WORDS = Set.of("can", "could", "would", "you", "please", "kindly", "pull", "fetch",
            "get", "show", "give", "me", "the", "a", "an", "latest", "last", "newest", "most", "recent", "invoice",
            "number", "which", "that", "is", "in", "on", "one", "duplicate", "check", "identifier", "queue", "and",
            "assigned", "assignee", "assignment", "to", "whom", "who", "it", "its", "what", "i2p", "from", "details");
    private static final String SQL = """
            SELECT TOP (1) e.InvoiceNo AS invoiceNumber, e.WINumber AS workItemNumber,
                   e.currQueue AS queue, CONVERT(varchar(33),e.CreatedDate,126) AS createdAt,
                   CONVERT(char(10),e.InvoiceDate,23) AS invoiceDate,
                   CASE WHEN e.assignTo IS NULL OR e.assignTo=0 THEN 'Unassigned'
                        ELSE COALESCE(NULLIF(u.UserName,''),CONCAT('User ID ',e.assignTo)) END AS assignedTo
            FROM [Toscana_I2P-SIT].dbo.NeonExternal e
            LEFT JOIN [Toscana_I2P-SIT].dbo.TOSCANA3_USER u ON u.UserIndex=e.assignTo
            WHERE e.lobID=92 AND (?=0 OR e.currQueue IN (?,?))
            ORDER BY e.CreatedDate DESC,e.RowID DESC
            """;
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    private final WorkspaceRepository workspaces;

    public I2pInvoiceLookup(JdbcTemplate jdbc, CurrentUser current, WorkspaceRepository workspaces) {
        this.jdbc = jdbc; this.current = current; this.workspaces = workspaces;
    }

    public static boolean isDetailQuestion(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        return q.matches("(?s).*\\bduplicate\\b.*") || q.matches("(?s).*\\binvoice\\b.*")
                && q.matches("(?s).*\\b(latest|last invoice|newest|most recent|assigned|assignee|number)\\b.*");
    }

    public ToscanaAnalyticsService.Answer answer(String question) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ").trim();
        List<String> tokens = List.of(q.split("\\s+"));
        if (!WORDS.containsAll(tokens) || !tokens.contains("invoice")
                || !(tokens.contains("latest") || tokens.contains("last") || tokens.contains("newest") || q.contains("most recent"))) {
            return ToscanaAnalyticsService.Answer.clarify("I can look up the latest I2P invoice and its assignee, optionally in Duplicate Check or Duplicate Identifier. "
                    + "Try: Show the latest invoice in the duplicate queue. Additional filters and other detail requests are not supported yet.");
        }
        // The installed operational source has no tenant column; restrict detail access to its default-workspace administrators.
        if (!current.isAdmin() || !workspaces.findById(current.workspaceId()).map(w -> "default".equals(w.getSlug())).orElse(false)) {
            throw new ForbiddenException("I2P invoice details require an administrator in the source's default workspace.");
        }
        boolean duplicate = tokens.contains("duplicate");
        if (tokens.contains("queue") && !duplicate || !duplicate && (tokens.contains("check") || tokens.contains("identifier"))) {
            return ToscanaAnalyticsService.Answer.clarify("Which queue do you mean: Duplicate Check or Duplicate Identifier?");
        }
        String first = duplicate && tokens.contains("identifier") && !tokens.contains("check") ? "Duplicate Identifier" : "Duplicate Check";
        String second = duplicate && tokens.contains("check") && !tokens.contains("identifier") ? "Duplicate Check" : "Duplicate Identifier";
        List<Map<String, Object>> rows = jdbc.queryForList(SQL, duplicate ? 1 : 0, first, second);
        String scope = duplicate ? first.equals(second) ? first : "Duplicate Check or Duplicate Identifier" : "all I2P queues";
        String explanation = "Live SQL Server data from Toscana_I2P-SIT.dbo.NeonExternal, I2P LOB 92. "
                + "Current queue: " + scope + ". Latest means CreatedDate descending, then RowID descending; it does not mean invoice date or queue-entry time. "
                + "Assignee is the current assignTo user's login. Database dates have no timezone offset.";
        String answer;
        if (rows.isEmpty()) answer = "No I2P invoice currently matches " + scope + ".";
        else {
            Map<String, Object> row = rows.getFirst();
            Object number = row.get("invoiceNumber");
            answer = "The latest I2P invoice" + (duplicate ? " in " + scope : "") + " is "
                    + (number == null || number.toString().isBlank() ? "missing its invoice number" : number)
                    + " (work item " + row.get("workItemNumber") + "), assigned to " + row.get("assignedTo")
                    + ". Its current queue is " + row.get("queue") + ".";
        }
        QueryService.Lineage lineage = new QueryService.Lineage(SQL, List.of(duplicate ? "1" : "0", first, second),
                "Toscana_I2P-SIT (SQL Server)", List.of("dbo.NeonExternal", "dbo.TOSCANA3_USER"),
                "Latest I2P invoice by creation time", 0, "OPERATIONAL", false, null,
                List.of("lobID = 92", "Current queue: " + scope, "TOP 1; CreatedDate DESC, RowID DESC"));
        return new ToscanaAnalyticsService.Answer(answer, rows, explanation, "table", lineage, null);
    }
}
