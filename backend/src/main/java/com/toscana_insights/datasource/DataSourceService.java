package com.toscana_insights.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.common.SecretCipher;
import com.toscana_insights.ingestion.HttpFetcher;
import com.toscana_insights.ingestion.RestConnector;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Workspace;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSourceService {
    public static final List<String> TYPES = List.of("SQL_SERVER", "SAP_API", "SALESFORCE_API", "ICERTIS_API", "SHAREPOINT", "REST_API", "FILE_DUMP", "POSTGRESQL", "CSV_UPLOAD");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|apikey|api_key|credential).*");
    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z0-9_]{1,128}(\\.[A-Za-z0-9_]{1,128})?");

    private final DataSourceRepository repository;
    private final WorkspaceRepository workspaces;
    private final AuditService audit;
    private final CurrentUser current;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;
    private final JdbcSourceFactory jdbcFactory;
    private final RestConnector rest;
    private final HttpFetcher http;

    public DataSourceService(DataSourceRepository repository, WorkspaceRepository workspaces, AuditService audit, CurrentUser current, SecretCipher cipher,
                             ObjectMapper mapper, JdbcSourceFactory jdbcFactory, RestConnector rest, HttpFetcher http) {
        this.repository = repository; this.workspaces = workspaces; this.audit = audit; this.current = current; this.cipher = cipher;
        this.mapper = mapper; this.jdbcFactory = jdbcFactory; this.rest = rest; this.http = http;
    }

    @Transactional(readOnly = true)
    public List<DataSourceDtos.Response> all() {
        UUID ws = current.workspaceId();
        int staleMinutes = staleMinutes(ws);
        return repository.findByWorkspaceIdOrderByName(ws).stream().map(s -> toResponse(s, staleMinutes)).toList();
    }

    @Transactional
    public DataSourceDtos.Response create(DataSourceDtos.Request request, String actor) {
        validate(request);
        DataSourceConnection source = new DataSourceConnection(current.workspaceId(), request.name(), request.type());
        apply(source, request);
        if (hasText(request.secret())) source.setSecretCipher(cipher.encrypt(request.secret()));
        DataSourceConnection saved = repository.save(source);
        audit.record(actor, "CREATE", "DATA_SOURCE", saved.getId(), saved.getName() + " (" + saved.getType() + ")");
        return toResponse(saved, staleMinutes(saved.getWorkspaceId()));
    }

    @Transactional
    public DataSourceDtos.Response update(UUID id, DataSourceDtos.Request request, String actor) {
        validate(request);
        DataSourceConnection source = get(id);
        apply(source, request);
        if (hasText(request.secret())) source.setSecretCipher(cipher.encrypt(request.secret()));
        audit.record(actor, "UPDATE", "DATA_SOURCE", id, source.getName());
        return toResponse(source, staleMinutes(source.getWorkspaceId()));
    }

    @Transactional
    public void delete(UUID id, String actor) {
        DataSourceConnection source = get(id);
        repository.delete(source);
        audit.record(actor, "DELETE", "DATA_SOURCE", id, source.getName());
    }

    @Transactional
    public DataSourceDtos.TestResult test(UUID id, String actor) {
        DataSourceConnection source = get(id);
        DataSourceDtos.TestResult result;
        try {
            result = runTest(source);
        } catch (RuntimeException | SQLException e) {
            result = new DataSourceDtos.TestResult(false, safeMessage(e));
        }
        source.testResult(result.ok(), result.message());
        audit.record(actor, "TEST", "DATA_SOURCE", id, (result.ok() ? "ok: " : "failed: ") + result.message());
        return result;
    }

    @Transactional
    public DataSourceDtos.Response pause(UUID id, String actor) {
        DataSourceConnection source = get(id);
        source.pause();
        audit.record(actor, "PAUSE", "DATA_SOURCE", id, source.getName());
        return toResponse(source, staleMinutes(source.getWorkspaceId()));
    }

    @Transactional
    public DataSourceDtos.Response resume(UUID id, String actor) {
        DataSourceConnection source = get(id);
        source.resume();
        audit.record(actor, "RESUME", "DATA_SOURCE", id, source.getName());
        return toResponse(source, staleMinutes(source.getWorkspaceId()));
    }

    @Transactional
    public DataSourceDtos.Response rotateSecret(UUID id, String newSecret, String actor) {
        DataSourceConnection source = get(id);
        source.setSecretCipher(cipher.encrypt(newSecret));
        audit.record(actor, "ROTATE_SECRET", "DATA_SOURCE", id, source.getName());
        return toResponse(source, staleMinutes(source.getWorkspaceId()));
    }

    // ---- used by ingestion, catalog and query services ----
    @Transactional(readOnly = true)
    public DataSourceConnection get(UUID id) {
        return repository.findByIdAndWorkspaceId(id, current.workspaceId()).orElseThrow(() -> new NotFoundException("Data source", id));
    }

    /** Resolves the stored credential: encrypted secret first, then an env://NAME reference. Never log the result. */
    public String secret(DataSourceConnection source) {
        if (hasText(source.getSecretCipher())) return cipher.decrypt(source.getSecretCipher());
        String ref = source.getCredentialReference();
        if (ref != null && ref.startsWith("env://")) return System.getenv(ref.substring(6));
        return null;
    }

    public JsonNode config(DataSourceConnection source) {
        try { return hasText(source.getConfigJson()) ? mapper.readTree(source.getConfigJson()) : mapper.createObjectNode(); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored config is invalid"); }
    }

    public List<String> allowedTables(DataSourceConnection source) {
        List<String> out = new ArrayList<>();
        config(source).path("allowedTables").forEach(n -> out.add(n.asText()));
        return out;
    }

    public Connection connect(DataSourceConnection source) throws SQLException {
        if (source.isPaused()) throw new BadRequestException("Source is paused");
        String password = secret(source);
        if (password == null) throw new BadRequestException("No credential stored for this source");
        return jdbcFactory.open(source, password, config(source).path("trustServerCertificate").asBoolean(false));
    }

    // ---- internals ----
    private DataSourceDtos.TestResult runTest(DataSourceConnection source) throws SQLException {
        if (JdbcSourceFactory.isJdbc(source.getType())) {
            try (Connection c = connect(source); Statement st = c.createStatement()) {
                st.setQueryTimeout(10);
                st.execute("SELECT 1");
                return new DataSourceDtos.TestResult(true, "Connected with a read-only session; " + allowedTables(source).size() + " table(s) allowed");
            }
        }
        if ("REST_API".equals(source.getType())) {
            rest.probe(config(source), secret(source));
            return new DataSourceDtos.TestResult(true, "REST endpoint reachable");
        }
        if ("FILE_DUMP".equals(source.getType())) {
            JsonNode config = config(source);
            if (hasText(config.path("url").asText())) http.validate(config.get("url").asText());
            return new DataSourceDtos.TestResult(true, "Dump location is allowed" + (hasText(config.path("url").asText()) ? "" : " (manual upload only)"));
        }
        if ("CSV_UPLOAD".equals(source.getType())) return new DataSourceDtos.TestResult(true, "Upload source needs no connection");
        return new DataSourceDtos.TestResult(false, "No connector is implemented for " + source.getType() + " yet");
    }

    private void validate(DataSourceDtos.Request r) {
        if (!TYPES.contains(r.type())) throw new BadRequestException("Unsupported source type: " + r.type());
        Map<String, Object> config = r.config() == null ? Map.of() : r.config();
        config.keySet().forEach(k -> { if (SECRET_KEY.matcher(k).matches() && !"authHeader".equals(k) && !"nextCursorPath".equals(k)) throw new BadRequestException("Config key '" + k + "' looks like a secret; send credentials in the write-only 'secret' field"); });
        if (JdbcSourceFactory.isJdbc(r.type())) {
            if (!hasText(r.host()) || r.port() == null || r.port() < 1 || !hasText(r.databaseName()) || !hasText(r.username()))
                throw new BadRequestException("Database sources need host, port, databaseName and username (a read-only account)");
            if (!JdbcSourceFactory.SAFE_HOST.matcher(r.host()).matches() || !JdbcSourceFactory.SAFE_DB.matcher(r.databaseName()).matches())
                throw new BadRequestException("Host or database name contains unsupported characters");
            Object allowed = config.get("allowedTables");
            if (allowed instanceof List<?> list) list.forEach(t -> { if (!SAFE_TABLE.matcher(String.valueOf(t)).matches()) throw new BadRequestException("Invalid table name in allowedTables: " + t); });
        }
        JsonNode node = mapper.valueToTree(config);
        if ("REST_API".equals(r.type())) rest.validateConfig(node);
        if ("FILE_DUMP".equals(r.type())) {
            if (!hasText(node.path("table").asText())) throw new BadRequestException("Dump source needs config.table");
            if (hasText(node.path("url").asText())) http.validate(node.get("url").asText());
        }
    }

    private void apply(DataSourceConnection source, DataSourceDtos.Request r) {
        String config;
        try { config = r.config() == null || r.config().isEmpty() ? null : mapper.writeValueAsString(r.config()); }
        catch (JsonProcessingException e) { throw new BadRequestException("Invalid config"); }
        source.update(r.name(), r.type(), nz(r.host()), r.port() == null ? 0 : r.port(), nz(r.databaseName()), nz(r.username()), nz(r.credentialReference()), config, r.scheduleMinutes());
    }

    private DataSourceDtos.Response toResponse(DataSourceConnection s, int staleMinutes) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (hasText(s.getConfigJson())) {
            try { config = mapper.readValue(s.getConfigJson(), new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (JsonProcessingException ignored) { }
        }
        boolean stale = s.getScheduleMinutes() != null && ("FAILED".equals(s.getLastRunStatus())
                || s.getLastSuccessAt() == null || Duration.between(s.getLastSuccessAt(), Instant.now()).toMinutes() > staleMinutes);
        return new DataSourceDtos.Response(s.getId(), s.getName(), s.getType(), s.getHost(), s.getPort(), s.getDatabaseName(), s.getUsername(),
                s.getCredentialReference(), hasText(s.getSecretCipher()), s.getStatus(), config, s.getScheduleMinutes(), s.getNextRunAt(), s.getLastSuccessAt(),
                s.getLastRunStatus(), s.getLastError(), s.getLastTestedAt(), s.getLastTestMessage(), stale, s.getCreatedAt(), s.getUpdatedAt());
    }

    private int staleMinutes(UUID workspaceId) { return workspaces.findById(workspaceId).map(Workspace::getStaleAfterMinutes).orElse(1440); }
    private static boolean hasText(String s) { return s != null && !s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }

    /** Error text for users and audit: exception class plus message, without anything that could echo a credential. */
    private static String safeMessage(Exception e) {
        if (e instanceof BadRequestException || e instanceof HttpFetcher.FetchException) return e.getMessage();
        return e.getClass().getSimpleName() + (e instanceof SQLException ? " (check host, port, database and credentials)" : "");
    }
}
