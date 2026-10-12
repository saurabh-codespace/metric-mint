package com.toscana_insights.metric;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toscana_insights.audit.AuditService;
import com.toscana_insights.catalog.CatalogService;
import com.toscana_insights.catalog.CatalogService.ColumnMeta;
import com.toscana_insights.catalog.CatalogTable;
import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.common.NotFoundException;
import com.toscana_insights.workspace.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Governed metric definitions: draft/approved states, an append-only version history, comparison and rollback. */
@Service
public class MetricService {
    public static final List<String> AGGREGATIONS = List.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern EXPRESSION = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\s*[-+*/]\\s*[A-Za-z_][A-Za-z0-9_]*)*");

    private final SemanticMetricRepository repository;
    private final MetricVersionRepository versions;
    private final CatalogService catalog;
    private final CurrentUser current;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public MetricService(SemanticMetricRepository repository, MetricVersionRepository versions, CatalogService catalog, CurrentUser current, AuditService audit, ObjectMapper mapper) {
        this.repository = repository; this.versions = versions; this.catalog = catalog; this.current = current; this.audit = audit; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<MetricDtos.Response> all() { return repository.findByWorkspaceIdOrderByName(current.workspaceId()).stream().map(MetricDtos.Response::from).toList(); }

    @Transactional(readOnly = true)
    public SemanticMetric get(UUID id) { return repository.findByIdAndWorkspaceId(id, current.workspaceId()).orElseThrow(() -> new NotFoundException("Metric", id)); }

    @Transactional(readOnly = true)
    public SemanticMetric byKey(String key) { return repository.findByWorkspaceIdAndKeyName(current.workspaceId(), key).orElseThrow(() -> new NotFoundException("Metric", key)); }

    @Transactional
    public MetricDtos.Response create(MetricDtos.Request r, String actor) {
        if (repository.findByWorkspaceIdAndKeyName(current.workspaceId(), r.keyName()).isPresent()) throw new BadRequestException("A metric with key '" + r.keyName() + "' already exists");
        validate(r);
        SemanticMetric metric = new SemanticMetric(current.workspaceId(), actor);
        define(metric, r);
        metric.newDraftVersion(true);
        SemanticMetric saved = repository.save(metric);
        snapshot(saved, "CREATE", actor);
        audit.record(actor, "CREATE", "METRIC", saved.getId(), saved.getKeyName());
        return MetricDtos.Response.from(saved);
    }

    @Transactional
    public MetricDtos.Response update(UUID id, MetricDtos.Request r, String actor) {
        SemanticMetric metric = get(id);
        if (!metric.getKeyName().equals(r.keyName()) && repository.findByWorkspaceIdAndKeyName(current.workspaceId(), r.keyName()).isPresent())
            throw new BadRequestException("A metric with key '" + r.keyName() + "' already exists");
        validate(r);
        define(metric, r);
        metric.newDraftVersion(false);
        snapshot(metric, "EDIT", actor);
        audit.record(actor, "UPDATE", "METRIC", id, metric.getKeyName() + " v" + metric.getVersion() + " (draft)");
        return MetricDtos.Response.from(metric);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        SemanticMetric metric = get(id);
        versions.deleteAll(versions.findByMetricIdOrderByCreatedAtAsc(id));
        repository.delete(metric);
        audit.record(actor, "DELETE", "METRIC", id, metric.getKeyName());
    }

    /** Publishes the current draft. The definition must be bound to a catalogue table so it can be queried. */
    @Transactional
    public MetricDtos.Response approve(UUID id, String actor) {
        SemanticMetric metric = get(id);
        if (metric.getTableId() == null) throw new BadRequestException("Bind the metric to a catalogue table before approving it");
        metric.approve(actor);
        snapshot(metric, "APPROVE", actor);
        audit.record(actor, "APPROVE", "METRIC", id, metric.getKeyName() + " v" + metric.getVersion());
        return MetricDtos.Response.from(metric);
    }

    @Transactional
    public void markVerified(UUID id, String actor) {
        SemanticMetric metric = get(id);
        if (!"APPROVED".equals(metric.getStatus())) throw new BadRequestException("Only an approved metric can be verified");
        metric.markVerified(actor);
        snapshot(metric, "VERIFY", actor);
        audit.record(actor, "VERIFY", "METRIC", id, metric.getKeyName() + " v" + metric.getVersion());
    }

    @Transactional(readOnly = true)
    public List<MetricDtos.VersionView> history(UUID id) {
        get(id);
        return versions.findByMetricIdOrderByCreatedAtAsc(id).stream().map(v -> new MetricDtos.VersionView(v.getVersionNumber(), v.getStatus(), v.getChangeType(), v.getChangedBy(), v.getCreatedAt(), read(v.getSnapshotJson()))).toList();
    }

    /** Restores the last recorded state of an earlier version as a new version. */
    @Transactional
    public MetricDtos.Response rollback(UUID id, int version, String actor) {
        SemanticMetric metric = get(id);
        MetricVersion target = versions.findByMetricIdOrderByCreatedAtAsc(id).stream().filter(v -> v.getVersionNumber() == version).reduce((a, b) -> b)
                .orElseThrow(() -> new NotFoundException("Metric version", version));
        MetricDtos.Snapshot s = read(target.getSnapshotJson());
        metric.define(s.name(), s.keyName(), s.description(), s.aggregation(), s.sourceField(), s.active(), s.tableId(), s.dateColumn(), s.synonyms(), s.baselineValue());
        metric.restore(metric.getVersion() + 1, s.status(), s.approvedBy(), s.approvedAt(), s.verified());
        snapshot(metric, "ROLLBACK to v" + version, actor);
        audit.record(actor, "ROLLBACK", "METRIC", id, metric.getKeyName() + " to v" + version + " as v" + metric.getVersion());
        return MetricDtos.Response.from(metric);
    }

    @Transactional(readOnly = true)
    public List<MetricDtos.FieldChange> diff(UUID id, int from, int to) {
        List<MetricVersion> all = versions.findByMetricIdOrderByCreatedAtAsc(get(id).getId());
        MetricDtos.Snapshot a = read(last(all, from).getSnapshotJson()), b = read(last(all, to).getSnapshotJson());
        Map<String, Object> am = mapper.convertValue(a, new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        Map<String, Object> bm = mapper.convertValue(b, new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        List<MetricDtos.FieldChange> changes = new ArrayList<>();
        am.forEach((k, v) -> { if (!java.util.Objects.equals(v, bm.get(k))) changes.add(new MetricDtos.FieldChange(k, v, bm.get(k))); });
        return changes;
    }

    // ---- helpers ----
    private static MetricVersion last(List<MetricVersion> all, int version) {
        return all.stream().filter(v -> v.getVersionNumber() == version).reduce((x, y) -> y).orElseThrow(() -> new NotFoundException("Metric version", version));
    }

    private void define(SemanticMetric m, MetricDtos.Request r) {
        String synonyms = r.synonyms() == null ? null : String.join(",", r.synonyms().stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        m.define(r.name(), r.keyName(), r.description(), r.aggregation().toUpperCase(), r.sourceField().trim(), r.active(), r.tableId(), blank(r.dateColumn()), synonyms, r.baselineValue());
    }

    private void validate(MetricDtos.Request r) {
        String agg = r.aggregation().toUpperCase();
        if (!AGGREGATIONS.contains(agg)) throw new BadRequestException("aggregation must be one of " + AGGREGATIONS);
        String field = r.sourceField().trim();
        boolean star = field.equals("*");
        if (star && !agg.equals("COUNT")) throw new BadRequestException("'*' is only valid with COUNT");
        if (!star && !EXPRESSION.matcher(field).matches()) throw new BadRequestException("sourceField must be a column or a simple expression such as amount - discount");
        if (r.tableId() == null) return;
        CatalogTable table = catalog.table(r.tableId());
        List<ColumnMeta> columns = catalog.columns(table, true);
        if (!star) {
            Matcher m = IDENT.matcher(field);
            while (m.find()) {
                ColumnMeta column = columns.stream().filter(c -> c.name().equals(m.group())).findFirst().orElseThrow(() -> new BadRequestException("Unknown column '" + m.group() + "' on " + table.getName()));
                boolean numeric = column.type().equals("LONG") || column.type().equals("DECIMAL");
                if (!numeric && (List.of("SUM", "AVG").contains(agg) || !field.equals(column.name())))
                    throw new BadRequestException("Column '" + column.name() + "' is not numeric and cannot be used in " + agg + " or arithmetic");
            }
        }
        if (blank(r.dateColumn()) != null && columns.stream().noneMatch(c -> c.name().equals(r.dateColumn()) && (c.type().equals("DATE") || c.type().equals("TIMESTAMP"))))
            throw new BadRequestException("dateColumn must be a DATE or TIMESTAMP column of " + table.getName());
    }

    private void snapshot(SemanticMetric m, String type, String actor) {
        MetricDtos.Snapshot s = new MetricDtos.Snapshot(m.getName(), m.getKeyName(), m.getDescription(), m.getAggregation(), m.getSourceField(), m.isActive(), m.getTableId(),
                m.getDateColumn(), m.getSynonyms(), m.getBaselineValue(), m.getStatus(), m.getVersion(), m.getApprovedBy(), m.getApprovedAt(), m.isVerified());
        try { versions.save(new MetricVersion(m.getId(), m.getVersion(), m.getStatus(), actor, type, mapper.writeValueAsString(s))); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private MetricDtos.Snapshot read(String json) {
        try { return mapper.readValue(json, MetricDtos.Snapshot.class); } catch (JsonProcessingException e) { throw new IllegalStateException("Corrupt metric snapshot"); }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
