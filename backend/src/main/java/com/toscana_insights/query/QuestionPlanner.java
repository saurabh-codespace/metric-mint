package com.toscana_insights.query;

import com.toscana_insights.catalog.CatalogService;
import com.toscana_insights.catalog.CatalogService.ColumnMeta;
import com.toscana_insights.catalog.CatalogTable;
import com.toscana_insights.metric.MetricDtos;
import com.toscana_insights.metric.MetricService;
import com.toscana_insights.metric.SemanticMetric;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Turns a plain-language question into a QuerySpec using only approved vocabulary (metric names, synonyms, column labels).
 * It never writes SQL. Anything it cannot map to the catalogue produces a clarification question instead of a guess.
 */
@Service
public class QuestionPlanner {
    public record Plan(QuerySpec spec, String interpretation, String clarification) {
        static Plan ask(String clarification) { return new Plan(null, null, clarification); }
    }

    private static final Pattern BY = Pattern.compile("\\b(?:by|per) ([a-z0-9_ ]+?)(?= (?:for|in|during|last|this|where|over|since|top|with|year to date|ytd|today|yesterday)\\b|$)");
    private static final Pattern TOP = Pattern.compile("\\btop (\\d{1,3})(?: ([a-z_ ]+?))?(?= (?:by|for|in|last|this|per|where)\\b|$)");
    private static final Pattern LAST_DAYS = Pattern.compile("\\blast (\\d{1,4}) days\\b");
    private static final Pattern YEAR = Pattern.compile("\\bin (20\\d\\d|19\\d\\d)\\b");
    private static final Pattern FILTER = Pattern.compile("(?:where|for|with|in)\\s+([A-Za-z_ ]+?)\\s+(?:is|=|equals)\\s+\"?([^\"\\s?.,]+)\"?", Pattern.CASE_INSENSITIVE);

    private final MetricService metrics;
    private final CatalogService catalog;
    private final Clock clock;

    public QuestionPlanner(MetricService metrics, CatalogService catalog, Clock clock) { this.metrics = metrics; this.catalog = catalog; this.clock = clock; }

    public Plan plan(String question, QuerySpec previous) {
        String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ").replaceAll("\\s+", " ").trim() + " ";
        List<MetricDtos.Response> usable = metrics.all().stream().filter(m -> m.active() && m.tableId() != null).toList();

        MetricDtos.Response chosen = null;
        int best = 0;
        List<MetricDtos.Response> tied = new ArrayList<>();
        for (MetricDtos.Response m : usable) {
            int score = 0;
            for (String phrase : phrases(m)) if (mentions(q, phrase)) score = Math.max(score, phrase.length());
            if (score == 0) continue;
            if (score > best) { best = score; chosen = m; tied.clear(); tied.add(m); }
            else if (score == best) tied.add(m);
        }
        if (tied.size() > 1) return Plan.ask("Which metric do you mean: " + String.join(" or ", tied.stream().map(MetricDtos.Response::name).toList()) + "?");

        QuerySpec base = null;
        if (chosen == null) {
            if (previous == null) {
                return usable.isEmpty() ? Plan.ask("No governed metrics are defined yet. Ask an analyst to approve a metric first.")
                        : Plan.ask("I could not match that to a governed metric. Try one of: " + String.join(", ", usable.stream().map(MetricDtos.Response::name).toList()) + ".");
            }
            base = previous;
            chosen = usable.stream().filter(m -> m.keyName().equals(previous.metric())).findFirst().orElse(null);
            if (chosen == null) return Plan.ask("The metric from the previous question is no longer available. Which metric do you want?");
        }

        SemanticMetric metric = metrics.byKey(chosen.keyName());
        CatalogTable table = catalog.table(metric.getTableId());
        List<ColumnMeta> columns = catalog.columns(table, false);

        List<String> dimensions = base == null ? new ArrayList<>() : new ArrayList<>(base.dimensions());
        String grain = base == null ? null : base.timeGrain();
        boolean changed = base == null;
        Matcher by = BY.matcher(q);
        List<String> byPhrases = new ArrayList<>();
        if (by.find()) for (String part : by.group(1).split(" and ")) byPhrases.add(part.trim());
        Integer limit = base == null ? null : base.limit();
        Matcher top = TOP.matcher(q);
        if (top.find()) {
            limit = Integer.parseInt(top.group(1));
            changed = true;
            if (byPhrases.isEmpty() && top.group(2) != null && !top.group(2).isBlank()) byPhrases.add(top.group(2).trim());
        }
        if (!byPhrases.isEmpty()) {
            dimensions.clear();
            grain = null;
            for (String phrase : byPhrases) {
                String g = timeGrain(phrase);
                if (g != null) {
                    if (g.equals("quarter") || g.equals("week")) return Plan.ask("Grouping by " + g + " is not supported yet. Group by day, month or year instead.");
                    if (metric.getDateColumn() == null) return Plan.ask("'" + metric.getName() + "' has no date column, so it cannot be grouped by time.");
                    dimensions.add("time"); grain = g;
                    continue;
                }
                ColumnMeta column = findColumn(columns, phrase);
                if (column == null) {
                    List<String> available = columns.stream().filter(c -> !"MEASURE".equals(c.role())).map(c -> c.label().isBlank() ? c.name() : c.label()).toList();
                    return Plan.ask("I can't group '" + metric.getName() + "' by '" + phrase + "'. Available: " + String.join(", ", available) + (metric.getDateColumn() != null ? ", month, year, day" : "") + ".");
                }
                dimensions.add(column.name());
            }
            changed = true;
        }

        QuerySpec.DateRange range = base == null ? null : base.dateRange();
        LocalDate today = LocalDate.now(clock);
        QuerySpec.DateRange found = dateRange(q, today);
        if (found != null) {
            if (metric.getDateColumn() == null) return Plan.ask("'" + metric.getName() + "' has no date column, so I can't restrict it to a time period.");
            range = found;
            changed = true;
        }

        List<QuerySpec.Filter> filters = base == null ? new ArrayList<>() : new ArrayList<>(base.filters());
        Matcher fm = FILTER.matcher(question);
        while (fm.find()) {
            ColumnMeta column = findColumn(columns, fm.group(1).toLowerCase(Locale.ROOT).trim());
            if (column == null) return Plan.ask("I don't know a field called '" + fm.group(1).trim() + "' for '" + metric.getName() + "'.");
            filters.removeIf(f -> f.column().equals(column.name()));
            filters.add(new QuerySpec.Filter(column.name(), "EQ", List.of(fm.group(2))));
            changed = true;
        }

        if (!changed) return Plan.ask("What would you like to know about " + metric.getName() + "? You can group it (by region), pick a period (last quarter) or filter it (region is EMEA).");
        QuerySpec spec = new QuerySpec(metric.getKeyName(), dimensions, filters, grain, range, null, null, limit);
        return new Plan(spec, describe(metric, columns, spec), null);
    }

    private static String describe(SemanticMetric metric, List<ColumnMeta> columns, QuerySpec spec) {
        StringBuilder s = new StringBuilder(metric.getName());
        if (!spec.dimensions().isEmpty()) s.append(" by ").append(String.join(" and ", spec.dimensions().stream().map(d -> d.equals("time") ? "time (" + spec.timeGrain() + ")" : d).toList()));
        if (spec.dateRange() != null) s.append(", ").append(spec.dateRange().from()).append(" up to (not including) ").append(spec.dateRange().to());
        for (QuerySpec.Filter f : spec.filters()) s.append(", where ").append(f.column()).append(" = ").append(f.values().get(0));
        if (spec.limit() != null) s.append(", top ").append(spec.limit());
        return s.append(". No other filters applied.").toString();
    }

    private QuerySpec.DateRange dateRange(String q, LocalDate today) {
        Matcher m = LAST_DAYS.matcher(q);
        if (m.find()) return new QuerySpec.DateRange(today.minusDays(Long.parseLong(m.group(1))).toString(), today.plusDays(1).toString());
        m = YEAR.matcher(q);
        if (m.find()) { int y = Integer.parseInt(m.group(1)); return new QuerySpec.DateRange(LocalDate.of(y, 1, 1).toString(), LocalDate.of(y + 1, 1, 1).toString()); }
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate quarterStart = LocalDate.of(today.getYear(), ((today.getMonthValue() - 1) / 3) * 3 + 1, 1);
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        if (mentions(q, "last month")) return range(monthStart.minusMonths(1), monthStart);
        if (mentions(q, "this month")) return range(monthStart, monthStart.plusMonths(1));
        if (mentions(q, "last quarter")) return range(quarterStart.minusMonths(3), quarterStart);
        if (mentions(q, "this quarter")) return range(quarterStart, quarterStart.plusMonths(3));
        if (mentions(q, "last year")) return range(yearStart.minusYears(1), yearStart);
        if (mentions(q, "this year")) return range(yearStart, yearStart.plusYears(1));
        if (mentions(q, "year to date") || mentions(q, "ytd")) return range(yearStart, today.plusDays(1));
        if (mentions(q, "yesterday")) return range(today.minusDays(1), today);
        if (mentions(q, "today")) return range(today, today.plusDays(1));
        return null;
    }

    private static QuerySpec.DateRange range(LocalDate from, LocalDate to) { return new QuerySpec.DateRange(from.toString(), to.toString()); }

    private static String timeGrain(String phrase) {
        return switch (phrase) {
            case "month", "months", "monthly" -> "month";
            case "day", "days", "daily" -> "day";
            case "year", "years", "yearly", "annual" -> "year";
            case "quarter", "quarters", "quarterly" -> "quarter";
            case "week", "weeks", "weekly" -> "week";
            default -> null;
        };
    }

    private static ColumnMeta findColumn(List<ColumnMeta> columns, String phrase) {
        String p = phrase.trim();
        String singular = p.endsWith("s") ? p.substring(0, p.length() - 1) : p;
        for (ColumnMeta c : columns) {
            if ("MEASURE".equals(c.role())) continue;
            List<String> names = new ArrayList<>(List.of(c.name().toLowerCase(Locale.ROOT), c.name().replace('_', ' ').toLowerCase(Locale.ROOT), c.label().toLowerCase(Locale.ROOT)));
            if (c.synonyms() != null) c.synonyms().forEach(s -> names.add(s.toLowerCase(Locale.ROOT)));
            if (names.contains(p) || names.contains(singular)) return c;
        }
        return null;
    }

    private static List<String> phrases(MetricDtos.Response m) {
        List<String> out = new ArrayList<>(List.of(m.name().toLowerCase(Locale.ROOT), m.keyName().replace('_', ' ')));
        m.synonyms().forEach(s -> out.add(s.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean mentions(String q, String phrase) {
        return q.contains(" " + phrase + " ") || q.contains(" " + phrase + "s ");
    }
}
