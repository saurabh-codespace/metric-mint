package com.toscana_insights.ingestion;

import com.toscana_insights.common.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads rows into isolated analytics tables (schema.imp_*). Rows are first loaded into a staging table and validated;
 * the live table is only touched, inside one transaction, after staging succeeded, so a failed load keeps prior good data.
 */
@Component
public class TableImporter {
    public static final String SCHEMA = "toscana_insights";
    public enum Mode { REPLACE, APPEND, UPSERT }
    public enum Kind { STRING, LONG, DECIMAL, DATE, TIMESTAMP }
    public record Column(String name, Kind kind) {}
    public record BadRow(int row, String reason) {}
    public record Result(String physicalTable, List<Column> columns, long rowsLoaded, List<BadRow> badRows, long badRowCount) {}
    public record Parsed(List<Column> columns, List<Object[]> rows, List<BadRow> badRows, long badRowCount) {}

    private static final int MAX_BAD_REPORTED = 100;
    private static final int MAX_STRING = 4000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TableImporter(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

    public static String physicalName(UUID workspaceId, String logicalName) {
        String slug = sanitize(logicalName);
        String name = "imp_" + workspaceId.toString().substring(0, 8) + "_" + slug;
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    public static String sanitize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "col";
        return Character.isDigit(s.charAt(0)) ? "c_" + s : s;
    }

    /** Schema is left unquoted so H2 (upper-casing) and SQL Server resolve the same schema; table and column names are always quoted lower-case. */
    public static String qualified(String physical) { return SCHEMA + ".\"" + physical + "\""; }

    /** Normalises headers, infers column types from all rows (or reuses an existing table's types) and splits good/bad rows. */
    public Parsed parse(List<String> rawHeaders, List<Map<String, Object>> records, List<BadRow> upstreamBad, List<Column> fixedColumns) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String h : rawHeaders) {
            String base = sanitize(h);
            String n = base;
            for (int i = 2; !seen.add(n); i++) n = base + "_" + i;
            names.add(n);
        }
        List<Column> columns = new ArrayList<>();
        for (int c = 0; c < names.size(); c++) {
            Kind kind = fixedColumns != null ? kindOf(fixedColumns, names.get(c)) : infer(records, rawHeaders.get(c));
            columns.add(new Column(names.get(c), kind));
        }
        List<BadRow> bad = new ArrayList<>(upstreamBad);
        long badCount = upstreamBad.size();
        List<Object[]> rows = new ArrayList<>();
        int rowNo = 0;
        for (Map<String, Object> record : records) {
            rowNo++;
            Object[] values = new Object[columns.size()];
            String problem = null;
            for (int c = 0; c < columns.size() && problem == null; c++) {
                Object raw = record.get(rawHeaders.get(c));
                try { values[c] = coerce(raw, columns.get(c).kind()); }
                catch (RuntimeException e) { problem = "column '" + columns.get(c).name() + "': " + e.getMessage(); }
            }
            if (problem == null) rows.add(values);
            else { badCount++; if (bad.size() < MAX_BAD_REPORTED) bad.add(new BadRow(rowNo, problem)); }
        }
        return new Parsed(columns, rows, bad, badCount);
    }

    public List<Column> existingColumns(String physical) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE UPPER(table_schema) = UPPER(?) AND table_name = ? ORDER BY ordinal_position", SCHEMA, physical);
        List<Column> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(new Column(String.valueOf(r.get("column_name")).toLowerCase(Locale.ROOT), kindFromSqlType(String.valueOf(r.get("data_type")))));
        return out;
    }

    public Result load(UUID workspaceId, String logicalName, Parsed parsed, Mode mode, String primaryKey) {
        String physical = physicalName(workspaceId, logicalName);
        String staging = physical + "_stg";
        List<Column> columns = parsed.columns();
        if (columns.isEmpty()) throw new BadRequestException("No columns found");
        if (parsed.rows().isEmpty()) throw new BadRequestException("No valid rows to import (" + parsed.badRowCount() + " bad rows)");
        List<Column> existing = existingColumns(physical);
        boolean exists = !existing.isEmpty();
        if (exists && mode != Mode.REPLACE && !sameNames(existing, columns))
            throw new BadRequestException("Schema drift: incoming columns " + names(columns) + " differ from existing " + names(existing) + ". Use REPLACE after review.");
        String pk = primaryKey == null || primaryKey.isBlank() ? null : sanitize(primaryKey);
        if (mode == Mode.UPSERT && (pk == null || columns.stream().noneMatch(c -> c.name().equals(pk))))
            throw new BadRequestException("UPSERT needs a primaryKey that is one of the columns");
        List<Object[]> rows = mode == Mode.UPSERT ? dedupeByKey(parsed.rows(), indexOf(columns, pk)) : parsed.rows();

        jdbc.execute("DROP TABLE IF EXISTS " + qualified(staging));
        jdbc.execute(createSql(staging, columns));
        try {
            String insert = "INSERT INTO " + qualified(staging) + " (" + columnList(columns) + ") VALUES (" + "?,".repeat(columns.size() - 1) + "?)";
            for (int i = 0; i < rows.size(); i += 500) jdbc.batchUpdate(insert, rows.subList(i, Math.min(rows.size(), i + 500)));
            Long staged = jdbc.queryForObject("SELECT COUNT(*) FROM " + qualified(staging), Long.class);
            if (staged == null || staged != rows.size()) throw new IllegalStateException("Staging validation failed: expected " + rows.size() + " rows, found " + staged);
            boolean recreate = exists && mode == Mode.REPLACE && !sameTypes(existing, columns);
            tx.executeWithoutResult(status -> {
                if (recreate) jdbc.execute("DROP TABLE " + qualified(physical));
                if (!exists || recreate) jdbc.execute(createSql(physical, columns));
                String cols = columnList(columns);
                if (mode == Mode.REPLACE) jdbc.execute("DELETE FROM " + qualified(physical));
                if (mode == Mode.UPSERT) jdbc.execute("DELETE FROM " + qualified(physical) + " WHERE \"" + pk + "\" IN (SELECT \"" + pk + "\" FROM " + qualified(staging) + ")");
                jdbc.execute("INSERT INTO " + qualified(physical) + " (" + cols + ") SELECT " + cols + " FROM " + qualified(staging));
            });
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + qualified(staging));
        }
        return new Result(physical, columns, rows.size(), parsed.badRows(), parsed.badRowCount());
    }

    public long count(String physical) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + qualified(physical), Long.class);
        return n == null ? 0 : n;
    }

    // ---- helpers ----
    private static String columnList(List<Column> columns) {
        return String.join(",", columns.stream().map(c -> "\"" + c.name() + "\"").toList());
    }

    private static String createSql(String table, List<Column> columns) {
        List<String> defs = columns.stream().map(c -> "\"" + c.name() + "\" " + sqlType(c.kind())).toList();
        return "CREATE TABLE " + qualified(table) + " (" + String.join(", ", defs) + ")";
    }

    private static String sqlType(Kind kind) {
        return switch (kind) {
            case LONG -> "BIGINT";
            case DECIMAL -> "DECIMAL(38,6)";
            case DATE -> "DATE";
            case TIMESTAMP -> "DATETIME2";
            case STRING -> "NVARCHAR(" + MAX_STRING + ")";
        };
    }

    private static Kind kindFromSqlType(String type) {
        String t = type.toUpperCase(Locale.ROOT);
        if (t.contains("TIMESTAMP") || t.contains("DATETIME")) return Kind.TIMESTAMP;
        if (t.equals("DATE")) return Kind.DATE;
        if (t.contains("INT")) return Kind.LONG;
        if (t.contains("DECIMAL") || t.contains("NUMERIC") || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL")) return Kind.DECIMAL;
        return Kind.STRING;
    }

    private static Kind kindOf(List<Column> fixed, String name) {
        return fixed.stream().filter(c -> c.name().equals(name)).map(Column::kind).findFirst().orElse(Kind.STRING);
    }

    private static Kind infer(List<Map<String, Object>> records, String header) {
        boolean anyValue = false, isLong = true, isDecimal = true, isDate = true, isTs = true;
        for (Map<String, Object> r : records) {
            Object v = r.get(header);
            if (v == null || String.valueOf(v).isBlank()) continue;
            anyValue = true;
            if (isLong && !tryParse(v, x -> coerce(x, Kind.LONG))) isLong = false;
            if (isDecimal && !tryParse(v, x -> coerce(x, Kind.DECIMAL))) isDecimal = false;
            if (isDate && !tryParse(v, x -> coerce(x, Kind.DATE))) isDate = false;
            if (isTs && !tryParse(v, x -> coerce(x, Kind.TIMESTAMP))) isTs = false;
            if (!isLong && !isDecimal && !isDate && !isTs) return Kind.STRING;
        }
        if (!anyValue) return Kind.STRING;
        return isLong ? Kind.LONG : isDecimal ? Kind.DECIMAL : isDate ? Kind.DATE : isTs ? Kind.TIMESTAMP : Kind.STRING;
    }

    private static boolean tryParse(Object v, Function<Object, Object> f) {
        try { f.apply(v); return true; } catch (RuntimeException e) { return false; }
    }

    public static Object coerce(Object raw, Kind kind) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return switch (kind) {
                case STRING -> {
                    if (s.length() > MAX_STRING) throw new IllegalArgumentException("text longer than " + MAX_STRING + " characters");
                    yield s;
                }
                case LONG -> number(s).longValueExact();
                case DECIMAL -> number(s);
                case DATE -> LocalDate.parse(s);
                case TIMESTAMP -> parseTimestamp(s);
            };
        } catch (ArithmeticException | java.time.format.DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("'" + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + "' is not a valid " + kind.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Rejects identifiers such as zip codes ("01234") so leading zeros are not silently lost. */
    private static BigDecimal number(String s) {
        if (s.matches("[-+]?0\\d+.*")) throw new NumberFormatException(s);
        return new BigDecimal(s);
    }

    private static LocalDateTime parseTimestamp(String s) {
        String t = s.replace(' ', 'T');
        if (t.endsWith("Z") || t.matches(".*[+-]\\d\\d:\\d\\d$")) return OffsetDateTime.parse(t).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return LocalDateTime.parse(t);
    }

    private static boolean sameNames(List<Column> a, List<Column> b) {
        return a.stream().map(Column::name).collect(java.util.stream.Collectors.toSet()).equals(b.stream().map(Column::name).collect(java.util.stream.Collectors.toSet()));
    }

    private static boolean sameTypes(List<Column> a, List<Column> b) {
        Map<String, Kind> m = new LinkedHashMap<>();
        a.forEach(c -> m.put(c.name(), c.kind()));
        return a.size() == b.size() && b.stream().allMatch(c -> c.kind() == m.get(c.name()));
    }

    private static List<String> names(List<Column> c) { return c.stream().map(Column::name).toList(); }

    private static int indexOf(List<Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(name)) return i;
        return -1;
    }

    private static List<Object[]> dedupeByKey(List<Object[]> rows, int keyIndex) {
        Map<Object, Object[]> byKey = new LinkedHashMap<>();
        for (Object[] r : rows) byKey.put(r[keyIndex], r);
        return new ArrayList<>(byKey.values());
    }
}
