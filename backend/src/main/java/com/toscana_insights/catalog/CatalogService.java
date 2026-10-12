package com.toscana_insights.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.datasource.DataSourceConnection;
import com.toscana_insights.datasource.DataSourceService;
import com.toscana_insights.datasource.JdbcSourceFactory;
import com.toscana_insights.ingestion.TableImporter;
import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Role;
import com.toscana_insights.workspace.WorkspaceRepository;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    public record ColumnMeta(String name, String type, String label, String description, String role, String sensitivity, List<String> synonyms) {}
    public record TableView(UUID id, UUID sourceId, String name, String description, String owner, long rowCount, Instant lastLoadedAt, boolean stale, List<ColumnMeta> columns) {}
    public record UpdateTable(String description, String owner, List<ColumnMeta> columns) {}
    public record RelationshipView(UUID id, UUID fromTableId, String fromColumn, UUID toTableId, String toColumn, String cardinality, String testStatus, String testMessage) {}
    public record PolicyView(UUID id, UUID tableId, String columnName, String matchValue, String role, String username) {}

    private static final List<String> ROLES = List.of("DIMENSION", "MEASURE", "TIME");
    private static final List<String> SENSITIVITY = List.of("PUBLIC", "RESTRICTED");
    private static final List<String> CARDINALITY = List.of("MANY_TO_ONE", "ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY");

    private final CatalogTableRepository tables;
    private final RelationshipRepository relationships;
    private final AccessPolicyRepository policies;
    private final DataSourceService sources;
    private final SourceAccess access;
    private final WorkspaceRepository workspaces;
    private final CurrentUser current;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public CatalogService(CatalogTableRepository tables, RelationshipRepository relationships, AccessPolicyRepository policies, DataSourceService sources,
                          SourceAccess access, WorkspaceRepository workspaces, CurrentUser current, AuditService audit, ObjectMapper mapper) {
        this.tables = tables; this.relationships = relationships; this.policies = policies; this.sources = sources; this.access = access;
        this.workspaces = workspaces; this.current = current; this.audit = audit; this.mapper = mapper;
    }

    // ---- tables ----
    @Transactional(readOnly = true)
    public List<TableView> list() {
        int staleMinutes = staleMinutes();
        return tables.findByWorkspaceIdOrderByName(current.workspaceId()).stream().map(t -> view(t, staleMinutes)).toList();
    }

    @Transactional(readOnly = true)
    public CatalogTable table(UUID id) {
        return tables.findByIdAndWorkspaceId(id, current.workspaceId()).orElseThrow(() -> new NotFoundException("Table", id));
    }

    @Transactional(readOnly = true)
    public CatalogTable tableByName(String name) {
        return tables.findByWorkspaceIdAndName(current.workspaceId(), name).orElseThrow(() -> new NotFoundException("Table", name));
    }

    /** All columns of a table, or only those the caller may see (RESTRICTED columns are for owners/admins). */
    public List<ColumnMeta> columns(CatalogTable table, boolean includeRestricted) {
        List<ColumnMeta> all = readColumns(table.getColumnsJson());
        return includeRestricted || current.isAdmin() ? all : all.stream().filter(c -> !"RESTRICTED".equals(c.sensitivity())).toList();
    }

    @Transactional
    public TableView update(UUID id, UpdateTable request, String actor) {
        CatalogTable table = table(id);
        List<ColumnMeta> existing = readColumns(table.getColumnsJson());
        List<ColumnMeta> merged = existing;
        if (request.columns() != null) {
            Map<String, ColumnMeta> byName = new java.util.LinkedHashMap<>();
            existing.forEach(c -> byName.put(c.name(), c));
            for (ColumnMeta in : request.columns()) {
                ColumnMeta old = byName.get(in.name());
                if (old == null) throw new BadRequestException("Unknown column: " + in.name());
                if (in.role() != null && !ROLES.contains(in.role())) throw new BadRequestException("role must be one of " + ROLES);
                if (in.sensitivity() != null && !SENSITIVITY.contains(in.sensitivity())) throw new BadRequestException("sensitivity must be one of " + SENSITIVITY);
                byName.put(old.name(), new ColumnMeta(old.name(), old.type(), nz(in.label(), old.label()), nz(in.description(), old.description()),
                        nz(in.role(), old.role()), nz(in.sensitivity(), old.sensitivity()), in.synonyms() == null ? old.synonyms() : in.synonyms()));
            }
            merged = new ArrayList<>(byName.values());
        }
        table.describe(request.description(), request.owner(), writeColumns(merged));
        audit.record(actor, "UPDATE", "CATALOG_TABLE", id, table.getName());
        return view(table, staleMinutes());
    }

    /** Called after an import: creates the catalogue entry or refreshes it, keeping descriptions and sensitivity already curated. */
    @Transactional
    public CatalogTable registerImported(UUID workspaceId, UUID sourceId, String logicalName, String physical, List<TableImporter.Column> imported, long rowCount) {
        CatalogTable table = tables.findByWorkspaceIdAndSourceIdAndPhysicalName(workspaceId, sourceId, physical).orElse(null);
        Map<String, ColumnMeta> previous = new java.util.HashMap<>();
        if (table != null) readColumns(table.getColumnsJson()).forEach(c -> previous.put(c.name(), c));
        List<ColumnMeta> columns = imported.stream().map(c -> {
            ColumnMeta old = previous.get(c.name());
            String type = c.kind().name();
            return old == null ? defaultColumn(c.name(), type) : new ColumnMeta(old.name(), type, old.label(), old.description(), old.role(), old.sensitivity(), old.synonyms());
        }).toList();
        if (table == null) {
            table = new CatalogTable(workspaceId, sourceId, uniqueName(workspaceId, logicalName), physical, writeColumns(columns));
            table.loaded(writeColumns(columns), rowCount);
            return tables.save(table);
        }
        table.loaded(writeColumns(columns), rowCount);
        return table;
    }

    /** Registers one table of a database source. Only tables on the source's allow-list can be discovered. */
    @Transactional
    public TableView registerRemote(UUID sourceId, String tableName, String actor) {
        DataSourceConnection source = sources.get(sourceId);
        if (!JdbcSourceFactory.isJdbc(source.getType())) throw new BadRequestException("Only database sources expose tables to register; imports register automatically");
        if (!sources.allowedTables(source).contains(tableName)) throw new BadRequestException("Table '" + tableName + "' is not on this source's allowed table list");
        String schema = tableName.contains(".") ? tableName.substring(0, tableName.indexOf('.')) : null;
        String bare = tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
        List<ColumnMeta> columns = new ArrayList<>();
        try (SourceAccess.Handle handle = access.open(sourceId)) {
            DatabaseMetaData meta = handle.connection().getMetaData();
            try (ResultSet rs = meta.getColumns(null, schema, bare, null)) {
                while (rs.next()) columns.add(defaultColumn(rs.getString("COLUMN_NAME"), kindOf(rs.getInt("DATA_TYPE")).name()));
            }
        } catch (SQLException e) {
            throw new BadRequestException("Could not read table metadata (check connection and permissions)");
        }
        if (columns.isEmpty()) throw new BadRequestException("Table '" + tableName + "' has no visible columns");
        UUID ws = current.workspaceId();
        CatalogTable table = tables.findByWorkspaceIdAndSourceIdAndPhysicalName(ws, sourceId, tableName).orElseGet(() -> new CatalogTable(ws, sourceId, uniqueName(ws, bare), tableName, writeColumns(columns)));
        table.describe(table.getDescription(), table.getOwnerName(), writeColumns(columns));
        audit.record(actor, "REGISTER", "CATALOG_TABLE", table.getId(), source.getName() + ": " + tableName);
        return view(tables.save(table), staleMinutes());
    }

    // ---- relationships ----
    @Transactional(readOnly = true)
    public List<RelationshipView> relationships() { return relationships.findByWorkspaceId(current.workspaceId()).stream().map(CatalogService::view).toList(); }

    @Transactional(readOnly = true)
    public List<Relationship> relationshipEntities() { return relationships.findByWorkspaceId(current.workspaceId()); }

    @Transactional
    public RelationshipView createRelationship(UUID fromTableId, String fromColumn, UUID toTableId, String toColumn, String cardinality, String actor) {
        CatalogTable from = table(fromTableId), to = table(toTableId);
        if (!CARDINALITY.contains(cardinality)) throw new BadRequestException("cardinality must be one of " + CARDINALITY);
        requireColumn(from, fromColumn);
        requireColumn(to, toColumn);
        Relationship saved = relationships.save(new Relationship(current.workspaceId(), fromTableId, fromColumn, toTableId, toColumn, cardinality));
        audit.record(actor, "CREATE", "RELATIONSHIP", saved.getId(), from.getName() + "." + fromColumn + " -> " + to.getName() + "." + toColumn);
        return testRelationship(saved.getId(), actor);
    }

    @Transactional
    public void deleteRelationship(UUID id, String actor) {
        relationships.delete(relationships.findByIdAndWorkspaceId(id, current.workspaceId()).orElseThrow(() -> new NotFoundException("Relationship", id)));
        audit.record(actor, "DELETE", "RELATIONSHIP", id, null);
    }

    /** Verifies a join is safe to use: same source, and the "one" side's key is unique so rows are not duplicated. */
    @Transactional
    public RelationshipView testRelationship(UUID id, String actor) {
        Relationship rel = relationships.findByIdAndWorkspaceId(id, current.workspaceId()).orElseThrow(() -> new NotFoundException("Relationship", id));
        CatalogTable from = table(rel.getFromTableId()), to = table(rel.getToTableId());
        String status = "SAFE", message;
        if (!from.getSourceId().equals(to.getSourceId())) { status = "UNSAFE"; message = "Cross-source joins are not supported; import both tables into one source first"; }
        else if (!List.of("MANY_TO_ONE", "ONE_TO_ONE").contains(rel.getCardinality())) { status = "UNSAFE"; message = "Joins that multiply rows (" + rel.getCardinality() + ") are not supported in governed queries"; }
        else {
            try (SourceAccess.Handle handle = access.open(from.getSourceId()); Statement st = handle.connection().createStatement()) {
                st.setQueryTimeout(15);
                String toCol = "\"" + rel.getToColumn() + "\"", fromCol = "\"" + rel.getFromColumn() + "\"";
                long total, distinct, orphans;
                try (ResultSet rs = st.executeQuery("SELECT COUNT(" + toCol + "), COUNT(DISTINCT " + toCol + ") FROM " + handle.qualify(to))) { rs.next(); total = rs.getLong(1); distinct = rs.getLong(2); }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + handle.qualify(from) + " f LEFT JOIN " + handle.qualify(to) + " t ON f." + fromCol + " = t." + toCol
                        + " WHERE t." + toCol + " IS NULL AND f." + fromCol + " IS NOT NULL")) { rs.next(); orphans = rs.getLong(1); }
                if (distinct < total) { status = "UNSAFE"; message = (total - distinct) + " duplicate key value(s) in " + to.getName() + "." + rel.getToColumn() + " would duplicate rows"; }
                else message = orphans == 0 ? "Key is unique and every row matches" : "Key is unique; " + orphans + " row(s) have no match and are dropped by inner joins";
            } catch (SQLException e) {
                status = "UNSAFE"; message = "Could not test the join (check the source connection)";
            }
        }
        rel.tested(status, message);
        audit.record(actor, "TEST", "RELATIONSHIP", id, status + ": " + message);
        return view(rel);
    }

    // ---- row policies ----
    @Transactional(readOnly = true)
    public List<PolicyView> policies() { return policies.findByWorkspaceId(current.workspaceId()).stream().map(CatalogService::view).toList(); }

    /** Row filters that apply to the current user on a table: matching role or username. */
    @Transactional(readOnly = true)
    public List<AccessPolicy> policiesFor(UUID tableId, Role role, String username) {
        return policies.findByWorkspaceIdAndTableId(current.workspaceId(), tableId).stream()
                .filter(p -> (p.getRoleName() == null && p.getTargetUsername() == null) || role.name().equals(p.getRoleName()) || username.equals(p.getTargetUsername())).toList();
    }

    @Transactional
    public PolicyView createPolicy(UUID tableId, String column, String matchValue, String role, String username, String actor) {
        CatalogTable table = table(tableId);
        requireColumn(table, column);
        if (role != null && !role.isBlank() && java.util.Arrays.stream(Role.values()).noneMatch(r -> r.name().equals(role))) throw new BadRequestException("Unknown role: " + role);
        AccessPolicy saved = policies.save(new AccessPolicy(current.workspaceId(), tableId, column, matchValue, blank(role), blank(username)));
        audit.record(actor, "CREATE", "ACCESS_POLICY", saved.getId(), table.getName() + "." + column + " in (" + matchValue + ") for " + (blank(role) != null ? blank(role) : blank(username) != null ? blank(username) : "everyone"));
        return view(saved);
    }

    @Transactional
    public void deletePolicy(UUID id, String actor) {
        policies.delete(policies.findByIdAndWorkspaceId(id, current.workspaceId()).orElseThrow(() -> new NotFoundException("Policy", id)));
        audit.record(actor, "DELETE", "ACCESS_POLICY", id, null);
    }

    // ---- helpers ----
    private TableView view(CatalogTable t, int staleMinutes) {
        boolean imported = t.getLastLoadedAt() != null;
        boolean stale = imported && Duration.between(t.getLastLoadedAt(), Instant.now()).toMinutes() > staleMinutes;
        return new TableView(t.getId(), t.getSourceId(), t.getName(), t.getDescription(), t.getOwnerName(), t.getRowCount(), t.getLastLoadedAt(), stale, columns(t, false));
    }

    private static RelationshipView view(Relationship r) {
        return new RelationshipView(r.getId(), r.getFromTableId(), r.getFromColumn(), r.getToTableId(), r.getToColumn(), r.getCardinality(), r.getTestStatus(), r.getTestMessage());
    }

    private static PolicyView view(AccessPolicy p) { return new PolicyView(p.getId(), p.getTableId(), p.getColumnName(), p.getMatchValue(), p.getRoleName(), p.getTargetUsername()); }

    private void requireColumn(CatalogTable table, String column) {
        if (readColumns(table.getColumnsJson()).stream().noneMatch(c -> c.name().equals(column))) throw new BadRequestException("Unknown column '" + column + "' on " + table.getName());
    }

    private String uniqueName(UUID workspaceId, String base) {
        String name = base;
        for (int i = 2; tables.findByWorkspaceIdAndName(workspaceId, name).isPresent(); i++) name = base + "_" + i;
        return name;
    }

    private static ColumnMeta defaultColumn(String name, String type) {
        String role = switch (type) { case "LONG", "DECIMAL" -> "MEASURE"; case "DATE", "TIMESTAMP" -> "TIME"; default -> "DIMENSION"; };
        return new ColumnMeta(name, type, name.replace('_', ' '), "", role, "PUBLIC", List.of());
    }

    private static TableImporter.Kind kindOf(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> TableImporter.Kind.LONG;
            case Types.NUMERIC, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.REAL -> TableImporter.Kind.DECIMAL;
            case Types.DATE -> TableImporter.Kind.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> TableImporter.Kind.TIMESTAMP;
            default -> TableImporter.Kind.STRING;
        };
    }

    List<ColumnMeta> readColumns(String json) {
        try { return mapper.readValue(json, new TypeReference<List<ColumnMeta>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Catalogue column metadata is corrupt"); }
    }

    private String writeColumns(List<ColumnMeta> columns) {
        try { return mapper.writeValueAsString(columns); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private int staleMinutes() { return workspaces.findById(current.workspaceId()).map(w -> w.getStaleAfterMinutes()).orElse(1440); }
    private static String nz(String value, String fallback) { return value == null ? fallback : value; }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
