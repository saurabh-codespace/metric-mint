package com.toscana_insights.query;

import com.toscana_insights.audit.AuditService;
import com.toscana_insights.catalog.AccessPolicy;
import com.toscana_insights.catalog.CatalogService;
import com.toscana_insights.catalog.CatalogService.ColumnMeta;
import com.toscana_insights.catalog.CatalogTable;
import com.toscana_insights.catalog.Relationship;
import com.toscana_insights.catalog.SourceAccess;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.BudgetExceededException;
import com.toscana_insights.ingestion.TableImporter;
import com.toscana_insights.ingestion.TableImporter.Kind;
import com.toscana_insights.metric.MetricService;
import com.toscana_insights.metric.SemanticMetric;
import com.toscana_insights.workspace.AppUser;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Workspace;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The only place where analytics SQL is produced. It compiles a QuerySpec against approved catalogue metadata, applies the
 * caller's row and column permissions, bounds rows/time/volume, runs it read-only and returns the evidence for the answer.
 */
@Service
public class QueryService {
    public record Lineage(String sql, List<String> parameters, String source, List<String> tables, String metricDefinition, int metricVersion,
                          String metricStatus, boolean metricVerified, Instant dataAsOf, List<String> filters) {}
    public record Result(String metric, String metricName, List<String> columns, List<Map<String, Object>> rows, boolean truncated, String label,
                         List<String> warnings, Lineage lineage, Instant executedAt, QuerySpec spec) {}

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> OPS = Set.of("EQ", "NE", "IN", "GT", "GTE", "LT", "LTE", "CONTAINS");

    private final MetricService metrics;
    private final CatalogService catalog;
    private final SourceAccess access;
    private final WorkspaceRepository workspaces;
    private final CurrentUser current;
    private final AuditService audit;

    public QueryService(MetricService metrics, CatalogService catalog, SourceAccess access, WorkspaceRepository workspaces, CurrentUser current, AuditService audit) {
        this.metrics = metrics; this.catalog = catalog; this.access = access; this.workspaces = workspaces; this.current = current; this.audit = audit;
    }

    private record Col(String alias, String name, Kind kind) {
        String sql() { return alias + ".\"" + name + "\""; }
    }
    private record Join(String alias, CatalogTable table, Relationship relationship, Map<String, ColumnMeta> visible, Map<String, ColumnMeta> all) {}

    public Result execute(QuerySpec spec) {
        AppUser user = current.user();
        Workspace ws = workspaces.findById(user.getWorkspaceId()).orElseThrow();
        if (audit.countToday(ws.getId(), "QUERY") >= ws.getMaxQueriesPerDay())
            throw new BudgetExceededException("Daily query budget of " + ws.getMaxQueriesPerDay() + " reached for this workspace");
        SemanticMetric metric = metrics.byKey(spec.metric());
        if (!metric.isActive() || metric.getTableId() == null) throw new BadRequestException("Metric '" + metric.getKeyName() + "' is not available for queries");
        CatalogTable table = catalog.table(metric.getTableId());
        Map<String, ColumnMeta> all = byName(catalog.columns(table, true));
        Map<String, ColumnMeta> visible = byName(catalog.columns(table, false));
        if (spec.dimensions().size() > 3 || spec.filters().size() > 10) throw new BadRequestException("At most 3 dimensions and 10 filters are allowed");

        for (String id : identifiers(metric.getSourceField())) {
            if (!visible.containsKey(id)) { denied(user, metric, id); throw new BadRequestException("The metric uses a column you are not allowed to access"); }
        }

        try (SourceAccess.Handle handle = access.open(table.getSourceId())) {
            List<Object> params = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            Map<String, Join> joins = new LinkedHashMap<>();
            List<String> selects = new ArrayList<>(), groupBy = new ArrayList<>(), labels = new ArrayList<>(), where = new ArrayList<>(), filterText = new ArrayList<>();

            for (int i = 0; i < spec.dimensions().size(); i++) {
                String ref = spec.dimensions().get(i);
                String expr;
                if (ref.equals("time")) {
                    if (metric.getDateColumn() == null) throw new BadRequestException("This metric has no date column to group by time");
                    String grain = spec.timeGrain() == null ? "month" : spec.timeGrain();
                    if (!List.of("day", "month", "year").contains(grain)) throw new BadRequestException("timeGrain must be day, month or year");
                    expr = handle.timeBucket("t.\"" + metric.getDateColumn() + "\"", grain);
                } else {
                    expr = resolve(ref, table, visible, all, joins, user, handle, notes).sql();
                }
                selects.add(expr + " AS dim_" + i);
                groupBy.add(expr);
                labels.add(ref);
            }
            String measure = measureSql(metric);
            selects.add(measure + " AS metric_value");
            labels.add(metric.getKeyName());

            for (AccessPolicy p : catalog.policiesFor(table.getId(), user.getRole(), user.getUsername())) where.add(policySql("t", p, all, params));
            if (!catalog.policiesFor(table.getId(), user.getRole(), user.getUsername()).isEmpty()) notes.add("A row-level access policy limits the rows included in this result");
            for (QuerySpec.Filter f : spec.filters()) {
                Col col = resolve(f.column(), table, visible, all, joins, user, handle, notes);
                where.add(filterSql(col, f, params));
                filterText.add(f.column() + " " + f.op() + " " + String.join(",", f.values()));
            }
            if (spec.dateRange() != null) {
                if (metric.getDateColumn() == null) throw new BadRequestException("This metric has no date column to filter by date");
                Kind kind = Kind.valueOf(all.get(metric.getDateColumn()).type());
                String col = "t.\"" + metric.getDateColumn() + "\"";
                if (spec.dateRange().from() != null) { where.add(col + " >= ?"); params.add(dateParam(spec.dateRange().from(), kind)); }
                if (spec.dateRange().to() != null) { where.add(col + " < ?"); params.add(dateParam(spec.dateRange().to(), kind)); }
                filterText.add("date " + spec.dateRange().from() + " to " + spec.dateRange().to());
            }
            for (Join j : joins.values()) {
                for (AccessPolicy p : catalog.policiesFor(j.table().getId(), user.getRole(), user.getUsername())) where.add(policySql(j.alias(), p, j.all(), params));
            }

            StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", selects)).append(" FROM ").append(handle.qualify(table)).append(" t");
            for (Join j : joins.values()) {
                Relationship r = j.relationship();
                sql.append(" LEFT JOIN ").append(handle.qualify(j.table())).append(' ').append(j.alias())
                        .append(" ON t.\"").append(r.getFromColumn()).append("\" = ").append(j.alias()).append(".\"").append(r.getToColumn()).append('"');
            }
            if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
            if (!groupBy.isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", groupBy));
            sql.append(" ORDER BY ").append(orderBy(spec, labels));

            int limit = Math.min(spec.limit() == null ? ws.getMaxRows() : Math.max(1, spec.limit()), ws.getMaxRows());
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement ps = handle.connection().prepareStatement(sql.toString())) {
                ps.setQueryTimeout(ws.getQueryTimeoutSeconds());
                ps.setMaxRows(limit + 1);
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rows.size() == limit) { truncated = true; break; }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 0; c < labels.size(); c++) row.put(labels.get(c), normalize(rs.getObject(c + 1)));
                        rows.add(row);
                    }
                }
            } catch (SQLTimeoutException e) {
                throw new BadRequestException("The query exceeded the " + ws.getQueryTimeoutSeconds() + " second limit; narrow the filters or date range");
            }

            List<String> warnings = new ArrayList<>(notes);
            Instant dataAsOf = table.getLastLoadedAt() != null ? table.getLastLoadedAt() : Instant.now();
            if (truncated) warnings.add("Result truncated to the first " + limit + " rows");
            if (rows.isEmpty()) warnings.add("No rows matched; this is an empty result, not a zero");
            if (!"APPROVED".equals(metric.getStatus())) warnings.add("Metric '" + metric.getName() + "' is a draft definition; treat this result as exploratory");
            else if (!metric.isVerified()) warnings.add("Metric '" + metric.getName() + "' is approved but has not been verified against a known total");
            if (table.getLastLoadedAt() != null && Duration.between(table.getLastLoadedAt(), Instant.now()).toMinutes() > ws.getStaleAfterMinutes())
                warnings.add("Data may be stale: last loaded " + table.getLastLoadedAt());
            if ("FAILED".equals(handle.source().getLastRunStatus()))
                warnings.add("The latest refresh of '" + handle.source().getName() + "' failed; results use the last good data from " + handle.source().getLastSuccessAt());
            boolean verified = metric.isVerified() && "APPROVED".equals(metric.getStatus()) && joins.values().stream().allMatch(j -> "SAFE".equals(j.relationship().getTestStatus()));

            List<String> tableNames = new ArrayList<>(List.of(table.getName()));
            joins.values().forEach(j -> tableNames.add(j.table().getName()));
            Lineage lineage = new Lineage(sql.toString(), params.stream().map(String::valueOf).toList(), handle.source().getName(), tableNames,
                    metric.getAggregation() + "(" + metric.getSourceField() + ")", metric.getVersion(), metric.getStatus(), metric.isVerified(), dataAsOf, filterText);
            audit.record(user.getUsername(), "QUERY", "METRIC", metric.getId(), summary(spec) + " -> " + rows.size() + " rows");
            return new Result(metric.getKeyName(), metric.getName(), labels, rows, truncated, verified ? "VERIFIED" : "EXPLORATORY", warnings, lineage, Instant.now(), spec);
        } catch (SQLException e) {
            audit.record(user.getUsername(), "QUERY_FAILED", "METRIC", metric.getId(), summary(spec) + ": " + e.getClass().getSimpleName());
            throw new BadRequestException("The query could not be run at the source. Check that the source is reachable and the table still exists.");
        }
    }

    // ---- compilation helpers ----
    private Col resolve(String ref, CatalogTable base, Map<String, ColumnMeta> visible, Map<String, ColumnMeta> all, Map<String, Join> joins, AppUser user,
                        SourceAccess.Handle handle, List<String> notes) {
        if (!ref.contains(".")) return col("t", ref, visible, all, user, base);
        String tableName = ref.substring(0, ref.indexOf('.')), column = ref.substring(ref.indexOf('.') + 1);
        Join join = joins.get(tableName);
        if (join == null) {
            CatalogTable other;
            try { other = catalog.tableByName(tableName); } catch (RuntimeException e) { throw new BadRequestException("Unknown or unavailable column '" + ref + "'"); }
            Relationship rel = catalog.relationshipEntities().stream().filter(r -> r.getFromTableId().equals(base.getId()) && r.getToTableId().equals(other.getId())).findFirst()
                    .orElseThrow(() -> new BadRequestException("No approved relationship links " + base.getName() + " to " + tableName));
            if (!"SAFE".equals(rel.getTestStatus())) throw new BadRequestException("The join from " + base.getName() + " to " + tableName + " has not passed its safety test");
            join = new Join("j" + (joins.size() + 1), other, rel, byName(catalog.columns(other, false)), byName(catalog.columns(other, true)));
            joins.put(tableName, join);
            notes.add("Joined " + tableName + " on " + rel.getFromColumn() + " = " + rel.getToColumn());
        }
        return col(join.alias(), column, join.visible(), join.all(), user, join.table());
    }

    private Col col(String alias, String name, Map<String, ColumnMeta> visible, Map<String, ColumnMeta> all, AppUser user, CatalogTable table) {
        ColumnMeta meta = visible.get(name);
        if (meta == null) {
            if (all.containsKey(name)) audit.record(user.getUsername(), "DENIED", "COLUMN", table.getId(), table.getName() + "." + name);
            throw new BadRequestException("Unknown or unavailable column '" + name + "'");
        }
        return new Col(alias, name, Kind.valueOf(meta.type()));
    }

    private String measureSql(SemanticMetric m) {
        String field = m.getSourceField();
        if (field.equals("*")) return "COUNT(*)";
        Matcher matcher = IDENT.matcher(field);
        StringBuilder expr = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(expr, Matcher.quoteReplacement("t.\"" + matcher.group() + "\""));
        matcher.appendTail(expr);
        return switch (m.getAggregation()) {
            case "COUNT_DISTINCT" -> "COUNT(DISTINCT " + expr + ")";
            case "AVG" -> "AVG(1.0 * (" + expr + "))";
            default -> m.getAggregation() + "(" + expr + ")";
        };
    }

    private String policySql(String alias, AccessPolicy p, Map<String, ColumnMeta> all, List<Object> params) {
        Kind kind = Kind.valueOf(all.get(p.getColumnName()).type());
        List<String> values = List.of(p.getMatchValue().split("\\s*,\\s*"));
        values.forEach(v -> params.add(TableImporter.coerce(v, kind)));
        return alias + ".\"" + p.getColumnName() + "\" IN (" + "?,".repeat(values.size() - 1) + "?)";
    }

    private String filterSql(Col col, QuerySpec.Filter f, List<Object> params) {
        String op = f.op() == null ? "" : f.op().toUpperCase(Locale.ROOT);
        if (!OPS.contains(op)) throw new BadRequestException("Filter op must be one of " + OPS);
        if (f.values() == null || f.values().isEmpty() || f.values().size() > 100) throw new BadRequestException("Filter on '" + f.column() + "' needs 1 to 100 values");
        try {
            if (op.equals("IN")) {
                f.values().forEach(v -> params.add(TableImporter.coerce(v, col.kind())));
                return col.sql() + " IN (" + "?,".repeat(f.values().size() - 1) + "?)";
            }
            if (op.equals("CONTAINS")) {
                if (col.kind() != Kind.STRING) throw new BadRequestException("CONTAINS only works on text columns");
                params.add("%" + f.values().get(0).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                return col.sql() + " LIKE ? ESCAPE '\\'";
            }
            params.add(TableImporter.coerce(f.values().get(0), col.kind()));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Filter on '" + f.column() + "': " + e.getMessage());
        }
        String sqlOp = switch (op) { case "EQ" -> "="; case "NE" -> "<>"; case "GT" -> ">"; case "GTE" -> ">="; case "LT" -> "<"; default -> "<="; };
        return col.sql() + " " + sqlOp + " ?";
    }

    private static Object dateParam(String iso, Kind kind) {
        try {
            LocalDate d = LocalDate.parse(iso);
            return kind == Kind.DATE ? d : d.atStartOfDay();
        } catch (java.time.format.DateTimeParseException e) { throw new BadRequestException("Dates must be ISO yyyy-MM-dd"); }
    }

    private String orderBy(QuerySpec spec, List<String> labels) {
        String dir = "asc".equalsIgnoreCase(spec.sortDir()) ? "ASC" : "DESC";
        String by = spec.sortBy();
        if (by == null || by.isBlank()) {
            if (!spec.dimensions().isEmpty() && spec.dimensions().get(0).equals("time")) return "dim_" + spec.dimensions().indexOf("time") + " ASC";
            return "metric_value DESC";
        }
        if (by.equals("metric") || by.equals(labels.get(labels.size() - 1))) return "metric_value " + dir;
        int i = spec.dimensions().indexOf(by);
        if (i < 0) throw new BadRequestException("sortBy must be 'metric' or one of the selected dimensions");
        return "dim_" + i + " " + dir;
    }

    private static Object normalize(Object v) {
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof java.sql.Date d) return d.toString();
        if (v instanceof java.math.BigDecimal b) return b.stripTrailingZeros().scale() < 0 ? b.setScale(0) : b.stripTrailingZeros();
        return v;
    }

    private static Set<String> identifiers(String expression) {
        Set<String> out = new LinkedHashSet<>();
        if (expression.equals("*")) return out;
        Matcher m = IDENT.matcher(expression);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static Map<String, ColumnMeta> byName(List<ColumnMeta> columns) {
        return columns.stream().collect(Collectors.toMap(ColumnMeta::name, c -> c, (a, b) -> a, LinkedHashMap::new));
    }

    private void denied(AppUser user, SemanticMetric metric, String column) {
        audit.record(user.getUsername(), "DENIED", "METRIC", metric.getId(), metric.getKeyName() + " uses restricted column " + column);
    }

    private static String summary(QuerySpec spec) {
        return spec.metric() + (spec.dimensions().isEmpty() ? "" : " by " + String.join(",", spec.dimensions())) + (spec.filters().isEmpty() ? "" : " filtered(" + spec.filters().size() + ")");
    }
}
