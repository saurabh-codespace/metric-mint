package com.toscana_insights.query;

import com.toscana_insights.catalog.CatalogService;
import com.toscana_insights.catalog.CatalogService.ColumnMeta;
import com.toscana_insights.catalog.CatalogTable;
import com.toscana_insights.catalog.Relationship;
import com.toscana_insights.metric.MetricDtos;
import com.toscana_insights.metric.MetricService;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/query")
public class QueryController {
    public record AskRequest(@NotBlank String question, QuerySpec previous) {}
    public record Dimension(String ref, String label, String type) {}
    public record MetricOption(String key, String name, String description, String status, boolean verified, List<Dimension> dimensions, boolean hasTime) {}

    private final QueryService queries;
    private final AskService asker;
    private final MetricService metrics;
    private final CatalogService catalog;

    public QueryController(QueryService queries, AskService asker, MetricService metrics, CatalogService catalog) {
        this.queries = queries; this.asker = asker; this.metrics = metrics; this.catalog = catalog;
    }

    @PostMapping @PreAuthorize(Roles.MEMBER)
    QueryService.Result run(@Valid @RequestBody @NotNull QuerySpec spec) { return queries.execute(spec); }

    @PostMapping("/ask") @PreAuthorize(Roles.MEMBER)
    AskService.Answer ask(@Valid @RequestBody AskRequest request) { return asker.ask(request.question(), request.previous()); }

    /** What the Explore UI may offer this user: metrics, their groupable fields (own table plus safely joined tables). */
    @GetMapping("/options") @PreAuthorize(Roles.MEMBER)
    List<MetricOption> options() {
        List<Relationship> relationships = catalog.relationshipEntities();
        List<MetricOption> out = new ArrayList<>();
        for (MetricDtos.Response m : metrics.all()) {
            if (!m.active() || m.tableId() == null) continue;
            CatalogTable table = catalog.table(m.tableId());
            List<Dimension> dims = new ArrayList<>();
            for (ColumnMeta c : catalog.columns(table, false)) if (!"MEASURE".equals(c.role())) dims.add(new Dimension(c.name(), c.label(), c.type()));
            for (Relationship r : relationships) {
                if (!r.getFromTableId().equals(table.getId()) || !"SAFE".equals(r.getTestStatus())) continue;
                CatalogTable other = catalog.table(r.getToTableId());
                for (ColumnMeta c : catalog.columns(other, false)) if ("DIMENSION".equals(c.role())) dims.add(new Dimension(other.getName() + "." + c.name(), other.getName() + " " + c.label(), c.type()));
            }
            out.add(new MetricOption(m.keyName(), m.name(), m.description(), m.status(), m.verified(), dims, m.dateColumn() != null));
        }
        return out;
    }
}
