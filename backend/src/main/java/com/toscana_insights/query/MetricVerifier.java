package com.toscana_insights.query;

import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.metric.MetricDtos;
import com.toscana_insights.metric.MetricService;
import com.toscana_insights.metric.SemanticMetric;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Compares a metric's unfiltered result with its known baseline total. Only a passing test lets a steward mark it verified. */
@Service
public class MetricVerifier {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");
    private final QueryService queries;
    private final MetricService metrics;

    public MetricVerifier(QueryService queries, MetricService metrics) { this.queries = queries; this.metrics = metrics; }

    public MetricDtos.TestResult test(UUID id) {
        SemanticMetric metric = metrics.get(id);
        if (metric.getBaselineValue() == null) throw new BadRequestException("Set a baselineValue (a known correct total) before testing this metric");
        QueryService.Result result = queries.execute(new QuerySpec(metric.getKeyName(), List.of(), List.of(), null, null, null, null, 1));
        Object value = result.rows().isEmpty() ? null : result.rows().get(0).get(metric.getKeyName());
        if (value == null) return new MetricDtos.TestResult(false, metric.getBaselineValue(), null, "The metric returned no value");
        BigDecimal actual = new BigDecimal(value.toString());
        BigDecimal expected = metric.getBaselineValue();
        BigDecimal allowed = expected.abs().multiply(TOLERANCE).max(new BigDecimal("0.005"));
        boolean passed = actual.subtract(expected).abs().compareTo(allowed) <= 0;
        String message = passed ? "Matches the known total" : "Differs from the known total by " + actual.subtract(expected).setScale(4, RoundingMode.HALF_UP).toPlainString();
        return new MetricDtos.TestResult(passed, expected, actual, message);
    }

    public MetricDtos.TestResult verify(UUID id, String actor) {
        MetricDtos.TestResult result = test(id);
        if (!result.passed()) throw new BadRequestException("Verification failed: " + result.message());
        metrics.markVerified(id, actor);
        return result;
    }
}
