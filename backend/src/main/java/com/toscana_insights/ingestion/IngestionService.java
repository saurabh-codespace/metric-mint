package com.toscana_insights.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.catalog.CatalogService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.datasource.DataSourceConnection;
import com.toscana_insights.datasource.DataSourceRepository;
import com.toscana_insights.datasource.DataSourceService;
import com.toscana_insights.ingestion.TableImporter.BadRow;
import com.toscana_insights.ingestion.TableImporter.Mode;
import com.toscana_insights.ingestion.TableImporter.Parsed;
import com.toscana_insights.workspace.CurrentUser;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Orchestrates every load into the analytics store and records a run for each attempt. Loads are idempotent: dumps are
 * skipped when the checksum was already imported, API loads upsert by primary key, and a failed run leaves prior data untouched.
 */
@Service
public class IngestionService {
    public record RunView(UUID id, String status, String trigger, long rowsLoaded, long rowsRejected, String message, String tableName,
                          Instant startedAt, Instant finishedAt, List<BadRow> badRows) {}
    public record Preview(List<TableImporter.Column> columns, List<Map<String, Object>> sample, long validRows, long badRowCount, List<BadRow> badRows) {}

    private final IngestionRunRepository runs;
    private final DataSourceRepository sourceRepository;
    private final DataSourceService sources;
    private final TableImporter importer;
    private final CatalogService catalog;
    private final RestConnector rest;
    private final HttpFetcher http;
    private final AuditService audit;
    private final CurrentUser current;
    private final long maxUploadBytes;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public IngestionService(IngestionRunRepository runs, DataSourceRepository sourceRepository, DataSourceService sources, TableImporter importer,
                            CatalogService catalog, RestConnector rest, HttpFetcher http, AuditService audit, CurrentUser current,
                            @Value("${app.ingestion.max-upload-bytes:10485760}") long maxUploadBytes) {
        this.runs = runs; this.sourceRepository = sourceRepository; this.sources = sources; this.importer = importer; this.catalog = catalog;
        this.rest = rest; this.http = http; this.audit = audit; this.current = current; this.maxUploadBytes = maxUploadBytes;
    }

    public Preview preview(byte[] content) {
        checkSize(content);
        CsvSupport.Table csv = CsvSupport.read(new java.io.ByteArrayInputStream(content));
        Parsed parsed = importer.parse(csv.headers(), csv.records(), csv.badRows(), null);
        List<Map<String, Object>> sample = csv.records().stream().limit(10).toList();
        return new Preview(parsed.columns(), sample, parsed.rows().size(), parsed.badRowCount(), parsed.badRows());
    }

    /** SRC-03: uploads a CSV into a named table. Valid rows load; bad rows are reported and skipped. */
    public RunView uploadCsv(DataSourceConnection source, String tableName, Mode mode, byte[] content, String actor) {
        if (!List.of("CSV_UPLOAD", "FILE_DUMP").contains(source.getType())) throw new BadRequestException("Uploads need a CSV_UPLOAD or FILE_DUMP source");
        if (mode == Mode.UPSERT) throw new BadRequestException("Choose REPLACE or APPEND for uploads");
        checkSize(content);
        return execute(source, "UPLOAD", actor, run -> loadCsv(source, tableName, mode, content, false, null));
    }

    /** Pulls a REST source or fetches its dump, as configured. */
    public RunView runNow(DataSourceConnection source, String trigger, String actor) {
        return execute(source, trigger, actor, run -> switch (source.getType()) {
            case "REST_API" -> pullRest(source);
            case "FILE_DUMP" -> pullDump(source);
            default -> throw new BadRequestException("This source type has no pull connector");
        });
    }

    public List<RunView> history(UUID sourceId) {
        sources.get(sourceId);
        return runs.findBySourceIdAndWorkspaceIdOrderByStartedAtDesc(sourceId, current.workspaceId(), PageRequest.of(0, 50)).stream().map(r -> view(r, List.of())).toList();
    }

    // ---- connectors ----
    private Outcome pullRest(DataSourceConnection source) {
        JsonNode config = sources.config(source);
        RestConnector.Result result = rest.fetch(config, sources.secret(source), source.getCursorValue());
        String table = config.get("table").asText();
        if (result.records().isEmpty()) return new Outcome("SUCCESS", 0, 0, null, "No new records", List.of(), result.newCursor(), null);
        Set<String> headers = new LinkedHashSet<>();
        result.records().forEach(r -> headers.addAll(r.keySet()));
        String pk = config.path("primaryKey").asText("");
        Mode mode = pk.isBlank() ? Mode.REPLACE : Mode.UPSERT;
        if (!pk.isBlank() && !headers.contains(pk)) throw new BadRequestException("primaryKey '" + pk + "' is not a field in the response");
        Parsed parsed = parse(source, table, List.copyOf(headers), result.records(), List.of(), mode);
        return load(source, table, parsed, mode, pk, result.newCursor(), null);
    }

    private Outcome pullDump(DataSourceConnection source) {
        JsonNode config = sources.config(source);
        String url = config.path("url").asText("");
        if (url.isBlank()) throw new BadRequestException("This dump source has no url; upload files manually");
        long max = Math.min(config.path("maxBytes").asLong(maxUploadBytes), 50_000_000L);
        byte[] content = http.get(url, Map.of("Accept", "text/csv"), max).body();
        Mode mode = "APPEND".equals(config.path("mode").asText("REPLACE")) ? Mode.APPEND : Mode.REPLACE;
        return loadCsv(source, config.get("table").asText(), mode, content, true, config.path("expectedSha256").asText(null));
    }

    /**
     * Named dump format "CSV dump v1": UTF-8 CSV with a header row, optionally pinned by SHA-256. Strict: a wrong checksum,
     * binary content or any malformed row rejects the whole dump. It is only ever parsed as data, never executed.
     */
    private Outcome loadCsv(DataSourceConnection source, String table, Mode mode, byte[] content, boolean strict, String expectedSha256) {
        String checksum = sha256(content);
        if (strict) {
            if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(checksum)) throw new BadRequestException("Checksum mismatch; dump rejected");
            if (checksum.equals(source.getLastChecksum())) return new Outcome("SKIPPED", 0, 0, null, "This dump was already imported", List.of(), null, null);
            for (byte b : content) if (b == 0) throw new BadRequestException("File is not a text CSV; dump rejected");
        }
        CsvSupport.Table csv = CsvSupport.read(new java.io.ByteArrayInputStream(content));
        if (strict && !csv.badRows().isEmpty()) throw new BadRequestException("Dump rejected: " + csv.badRows().size() + " malformed row(s), first: " + csv.badRows().get(0).reason());
        Parsed parsed = parse(source, table, csv.headers(), csv.records(), csv.badRows(), mode);
        return load(source, table, parsed, mode, null, null, strict ? checksum : null);
    }

    private Parsed parse(DataSourceConnection source, String table, List<String> headers, List<Map<String, Object>> records, List<BadRow> bad, Mode mode) {
        List<TableImporter.Column> fixed = mode == Mode.REPLACE ? null : importer.existingColumns(TableImporter.physicalName(source.getWorkspaceId(), table));
        return importer.parse(headers, records, bad, fixed == null || fixed.isEmpty() ? null : fixed);
    }

    private Outcome load(DataSourceConnection source, String table, Parsed parsed, Mode mode, String pk, String cursor, String checksum) {
        TableImporter.Result result = importer.load(source.getWorkspaceId(), table, parsed, mode, pk);
        catalog.registerImported(source.getWorkspaceId(), source.getId(), table, result.physicalTable(), result.columns(), importer.count(result.physicalTable()));
        String note = result.badRowCount() > 0 ? result.badRowCount() + " row(s) rejected" : "Loaded";
        return new Outcome("SUCCESS", result.rowsLoaded(), result.badRowCount(), result.physicalTable(), note, result.badRows(), cursor, checksum);
    }

    // ---- run bookkeeping ----
    private record Outcome(String status, long rows, long rejected, String physical, String message, List<BadRow> badRows, String cursor, String checksum) {}
    private interface Work { Outcome apply(IngestionRun run); }

    private RunView execute(DataSourceConnection source, String trigger, String actor, Work work) {
        if (source.isPaused()) throw new BadRequestException("Source is paused");
        if (!running.add(source.getId())) throw new BadRequestException("A run is already in progress for this source");
        IngestionRun run = runs.save(new IngestionRun(source.getWorkspaceId(), source.getId(), trigger));
        try {
            Outcome outcome = work.apply(run);
            run.finish(outcome.status(), outcome.rows(), outcome.rejected(), outcome.physical(), outcome.message());
            source.runSucceeded(outcome.cursor(), outcome.checksum());
            runs.save(run);
            sourceRepository.save(source);
            audit.record(source.getWorkspaceId(), actor, "INGEST", "DATA_SOURCE", source.getId(), outcome.status() + ": " + outcome.rows() + " rows, " + outcome.rejected() + " rejected");
            return view(run, outcome.badRows());
        } catch (RuntimeException e) {
            String message = e instanceof BadRequestException || e instanceof HttpFetcher.FetchException ? e.getMessage() : "Unexpected " + e.getClass().getSimpleName();
            run.finish("FAILED", 0, 0, null, message);
            source.runFailed(message);
            runs.save(run);
            sourceRepository.save(source);
            audit.record(source.getWorkspaceId(), actor, "INGEST_FAILED", "DATA_SOURCE", source.getId(), message);
            return view(run, List.of());
        } finally {
            running.remove(source.getId());
        }
    }

    private RunView view(IngestionRun r, List<BadRow> bad) {
        return new RunView(r.getId(), r.getStatus(), r.getTriggerType(), r.getRowsLoaded(), r.getRowsRejected(), r.getMessage(), r.getTableName(), r.getStartedAt(), r.getFinishedAt(), bad);
    }

    private void checkSize(byte[] content) {
        if (content.length > maxUploadBytes) throw new BadRequestException("File exceeds the " + (maxUploadBytes / 1024 / 1024) + " MB limit");
        if (content.length == 0) throw new BadRequestException("File is empty");
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
