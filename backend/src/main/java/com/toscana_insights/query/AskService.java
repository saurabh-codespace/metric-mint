package com.toscana_insights.query;

import com.toscana_insights.metric.MetricService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Question in, governed answer out: plan -> permission-checked query -> short summary with the evidence attached. */
@Service
public class AskService {
    public record Answer(String answer, String interpretation, String clarification, QueryService.Result result) {
        public boolean clarified() { return clarification != null; }
    }

    private final QuestionPlanner planner;
    private final QueryService queries;
    private final MetricService metrics;

    public AskService(QuestionPlanner planner, QueryService queries, MetricService metrics) { this.planner = planner; this.queries = queries; this.metrics = metrics; }

    public Answer ask(String question, QuerySpec previous) {
        QuestionPlanner.Plan plan = planner.plan(question, previous);
        if (plan.clarification() != null) return new Answer(plan.clarification(), null, plan.clarification(), null);
        QueryService.Result result = queries.execute(plan.spec());
        return new Answer(summarize(result), plan.interpretation(), null, result);
    }

    /** True when at least one approved metric exists, i.e. the governed path can plausibly answer. */
    public boolean hasGovernedMetrics() { return metrics.all().stream().anyMatch(m -> m.active() && m.tableId() != null); }

    private static String summarize(QueryService.Result r) {
        if (r.rows().isEmpty()) return "No data matched for " + r.metricName() + ".";
        String key = r.metric();
        if (r.columns().size() == 1) return r.metricName() + " is " + r.rows().get(0).get(key) + ".";
        Map<String, Object> top = r.rows().get(0);
        String dims = String.join(" / ", r.columns().subList(0, r.columns().size() - 1));
        StringBuilder who = new StringBuilder();
        for (String c : r.columns().subList(0, r.columns().size() - 1)) who.append(who.length() == 0 ? "" : " / ").append(top.get(c));
        return r.metricName() + " by " + dims + ": " + r.rows().size() + " group(s); first is " + who + " with " + top.get(key) + (r.truncated() ? " (truncated)" : "") + ".";
    }
}
