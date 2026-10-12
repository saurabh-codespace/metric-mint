package com.toscana_insights.template;

import com.toscana_insights.analytics.AiInsightService;
import com.toscana_insights.common.BadRequestException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.stereotype.Service;

import static com.toscana_insights.template.ReportTemplateDtos.*;

@Service
public class TemplateGenerationService {
    public record Request(@NotBlank @Size(max = 2000) String prompt, @NotBlank @Pattern(regexp = "live|sample") String dataMode) {}
    public record Response(RunResult result, boolean aiGenerated, String message, String clarification) {}
    public record Field(String name, String kind) {}
    public record CatalogSource(String type, String id, String name, String scope, List<Field> fields) {}
    private static final Set<String> DESIGN_WORDS = Set.of("create", "build", "generate", "make", "design", "show", "me", "i", "want", "need", "would", "like",
            "can", "could", "you", "please", "a", "an", "the", "my", "our", "with", "and", "dashboard", "dashboards", "report", "reports", "invoice", "invoices",
            "layout", "chart", "charts", "visualize", "visualise", "view", "compare", "comparison", "comparing", "use", "using", "as", "bar", "column", "line", "area",
            "pie", "donut", "table", "full", "half", "width", "modern", "clean", "simple", "overview", "summary", "executive", "management", "operational", "data", "live", "sample");
    private static final List<String> CHARTS = List.of("bar", "column", "line", "area", "pie", "donut", "table");
    private final ReportTemplateService templates;
    private final AiInsightService ai;

    public TemplateGenerationService(ReportTemplateService templates, AiInsightService ai) { this.templates = templates; this.ai = ai; }
    public boolean configured() { return ai.configured(); }

    public Response generate(Request request) {
        List<CatalogSource> catalog = new ArrayList<>();
        List<CatalogSource> unavailable = new ArrayList<>();
        try {
            for (SourceOption option : templates.sources()) {
                if (option.sample() != "sample".equals(request.dataMode())) continue;
                SourceData data;
                try { data = templates.source(option.type(), option.id()); }
                catch (RuntimeException e) {
                    unavailable.add(new CatalogSource(option.type(), option.id(), option.name(), "Unavailable", List.of()));
                    continue;
                }
                List<Field> fields = data.columns().stream().map(name -> {
                    List<Object> values = data.rows().stream().map(row -> row.get(name)).filter(Objects::nonNull).toList();
                    return new Field(name, !values.isEmpty() && values.stream().allMatch(Number.class::isInstance) ? "NUMBER" : "TEXT");
                }).toList();
                String scope = option.sample() ? "Synthetic demonstration records; no live business data."
                        : "connected".equals(option.type()) ? connectedScope(option.id())
                        : "Saved governed report. Existing query and current viewer permissions; no additional filters or regrouping.";
                catalog.add(new CatalogSource(option.type(), option.id(), option.name(), scope, fields));
            }
        } catch (RuntimeException e) {
            return clarify("The accessible source catalog could not be loaded. Check source access and connectivity, then retry.");
        }
        if (catalog.isEmpty()) return clarify("No accessible " + request.dataMode() + " sources are available. Live operational sources require a default-workspace administrator; otherwise save a governed report first, or choose sample data explicitly.");

        String prompt = normalize(request.prompt());
        if (unavailable.stream().flatMap(source -> aliases(source).stream()).anyMatch(alias -> (" " + prompt + " ").contains(" " + alias + " "))) {
            return clarify("A requested report could not be loaded with your current permissions. Check its source access and connectivity, then retry.");
        }
        String unavailableNotice = unavailable.isEmpty() ? "" : " Unavailable sources were excluded: " + String.join(", ", unavailable.stream().limit(5).map(CatalogSource::name).toList()) + ".";
        String remaining = " " + prompt.replace("side by side", "") + " ";
        Set<String> requested = new LinkedHashSet<>();
        List<Map.Entry<String, CatalogSource>> aliases = new ArrayList<>();
        for (CatalogSource source : catalog) for (String alias : aliases(source)) aliases.add(Map.entry(alias, source));
        aliases.sort(Comparator.comparingInt((Map.Entry<String, CatalogSource> entry) -> entry.getKey().length()).reversed());
        for (var alias : aliases) {
            String phrase = " " + alias.getKey() + " ";
            if (remaining.contains(phrase)) {
                requested.add(key(alias.getValue()));
                remaining = remaining.replace(" from" + phrase, " ").replace(" for" + phrase, " ").replace(" of" + phrase, " ");
                remaining = remaining.replace(phrase, " ");
            }
        }
        boolean generated = ai.configured();
        if (remaining.matches("(?s).*\\b(where|excluding|exclude|except|filter|filtered|today|yesterday|ytd|19[0-9]{2}|20[0-9]{2})\\b.*")
                || remaining.matches("(?s).*\\b(last|past|next|this)\\s+(?:[0-9]+\\s+)?(days?|weeks?|months?|quarters?|years?)\\b.*")
                || remaining.matches("(?s).*\\b(region|country|vendor|supplier|status)\\s+(is|equals)\\b.*")
                || request.prompt().matches("(?s).*[<>=].*")) {
            return clarify("This layout cannot add date ranges or data filters. Create a governed report with those conditions first, then request a dashboard using that saved report.");
        }
        // The no-provider starter supports named reports only; configured AI may interpret free-form layout requests.
        if (!generated && (requested.isEmpty() || Arrays.stream(remaining.strip().split("\\s+")).filter(word -> !word.isEmpty()).anyMatch(word -> !DESIGN_WORDS.contains(word)))) {
            return clarify("Name the available reports without additional metrics, filters, date ranges, or regrouping: "
                    + String.join(", ", catalog.stream().map(CatalogSource::name).toList()) + ". Apply any required conditions in a governed saved report first.");
        }
        Definition definition;
        if (generated) {
            AiInsightService.TemplatePlan plan;
            try { plan = ai.generateTemplate(request.prompt(), request.dataMode(), catalog); }
            catch (RuntimeException e) { return clarify("AI generation did not complete. Please retry or ask your administrator to check the provider configuration."); }
            if (plan == null) return clarify("AI generation returned no layout. Please retry.");
            if (plan.clarification() != null && !plan.clarification().isBlank()) return clarify(plan.clarification());
            definition = plan.definition();
        } else {
            List<String> chartChoices = CHARTS.stream().filter(chart -> Arrays.asList(prompt.split(" ")).contains(chart)).toList();
            if (chartChoices.size() > 1) return clarify("AI is not configured. Choose one chart style for the starter, or change each chart in the editor afterwards.");
            if (prompt.matches(".*\\bfull\\b.*") && prompt.matches(".*\\bhalf\\b.*")) return clarify("Choose full width or half width for this starter; edit individual widget widths afterwards.");
            String width = prompt.matches(".*\\bfull\\b.*") ? "full" : "half";
            List<Widget> widgets = new ArrayList<>();
            for (CatalogSource source : catalog) if (requested.contains(key(source))) {
                String value = defaultValue(source);
                String label = source.fields().stream().filter(field -> "TEXT".equals(field.kind())).map(Field::name).findFirst().orElse(null);
                String chart = chartChoices.isEmpty() ? (label == null || value == null || "high-aging-sla".equals(source.id()) ? "table" : "invoice-intake".equals(source.id()) ? "line" : "bar") : chartChoices.getFirst();
                widgets.add(new Widget("widget-" + (widgets.size() + 1), source.name(), source.type(), source.id(), chart, label, value,
                        "table".equals(chart) ? source.fields().stream().map(Field::name).toList() : List.of(), width));
            }
            definition = new Definition(1, "sample".equals(request.dataMode()) ? "Sample report dashboard" : "Live report dashboard",
                    "Editable starter using the selected existing reports. No additional filters or date ranges applied.", widgets);
        }
        try {
            validateCatalog(definition, catalog, requested, generated);
            RunResult result = templates.runDefinition(definition);
            return new Response(result, generated, (generated ? "AI created this editable layout from accessible source metadata. Review it before saving or publishing."
                    : "AI is not configured. This is an editable starter built from the named reports; no AI generation was used.") + unavailableNotice, null);
        } catch (RuntimeException e) {
            return clarify("The proposed layout could not be validated against the selected sources. Check source access and available fields, then retry or edit a template manually.");
        }
    }

    private static void validateCatalog(Definition definition, List<CatalogSource> catalog, Set<String> requested, boolean aiGenerated) {
        if (definition == null || definition.widgets() == null) throw new BadRequestException("Missing generated definition");
        Map<String, CatalogSource> sources = new HashMap<>();
        catalog.forEach(source -> sources.put(key(source), source));
        Set<String> used = new HashSet<>();
        for (Widget widget : definition.widgets()) {
            if (widget == null) throw new BadRequestException("Missing widget");
            String key = widget.sourceType() + ":" + widget.sourceId();
            CatalogSource source = sources.get(key);
            if (source == null || !aiGenerated && !requested.contains(key)) throw new BadRequestException("Source not in requested catalog");
            Set<String> fields = new HashSet<>();
            source.fields().forEach(field -> fields.add(field.name()));
            if (widget.columns() == null || !fields.containsAll(widget.columns())
                    || widget.labelKey() != null && !fields.contains(widget.labelKey())
                    || widget.valueKey() != null && !fields.contains(widget.valueKey())) throw new BadRequestException("Unknown generated field");
            if (!"table".equals(widget.chart()) && source.fields().stream().noneMatch(field -> field.name().equals(widget.valueKey()) && "NUMBER".equals(field.kind()))) {
                throw new BadRequestException("Chart value must be numeric");
            }
            used.add(key);
        }
        if (!used.containsAll(requested)) throw new BadRequestException("The generated layout omitted a requested report");
    }

    private static List<String> aliases(CatalogSource source) {
        List<String> names = new ArrayList<>(List.of(normalize(source.name()), normalize(source.id())));
        if ("report".equals(source.type())) return names;
        names.addAll(switch (source.id()) {
            case "invoice-intake" -> List.of("monthly volume", "monthly invoice volume", "invoice intake", "invoice intake trend");
            case "invoice-status" -> List.of("status distribution", "invoice status distribution");
            case "queue-management" -> List.of("queue management", "queue management dashboard");
            case "utilization" -> List.of("utilization", "utilization dashboard");
            case "on-hold" -> List.of("on hold", "on hold report");
            case "csat-dashboard" -> List.of("csat", "customer satisfaction");
            case "savings-collection" -> List.of("savings", "savings collection");
            case "high-aging-sla" -> List.of("high aging sla", "aging bands");
            default -> List.of();
        });
        return names;
    }

    private static String defaultValue(CatalogSource source) {
        String preferred = switch (source.id()) {
            case "queue-management" -> "resolved";
            case "utilization" -> "utilizationPercent";
            case "csat-dashboard" -> "scorePercent";
            case "savings-collection" -> "totalUsd";
            default -> "count";
        };
        return source.fields().stream().filter(field -> "NUMBER".equals(field.kind())).map(Field::name)
                .sorted(Comparator.comparingInt(name -> name.equals(preferred) ? 0 : 1)).findFirst().orElse(null);
    }

    private static String connectedScope(String id) {
        String grouping = switch (id) {
            case "invoice-intake" -> "Monthly counts by creation month; latest 12 available months. Daily/weekly/yearly regrouping is unavailable.";
            case "invoice-status" -> "Up to 12 most common invoice statuses across available history.";
            case "on-hold" -> "Counts by existing on-hold status across available history.";
            case "high-aging-sla" -> "Open items grouped jointly by aging band and status; retain both fields, preferably in a table.";
            default -> "Existing report grouping only.";
        };
        return "Operational Accounts Payable LOB90 only. " + grouping + " No custom filters or date ranges. Not I2P LOB92 queues.";
    }

    private static String key(CatalogSource source) { return source.type() + ":" + source.id(); }
    private static String normalize(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip().replaceAll("\\s+", " "); }
    private static Response clarify(String message) { return new Response(null, false, message, message); }
}
