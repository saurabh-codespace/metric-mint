package com.toscana_insights.query;

import java.util.List;

/**
 * A structured, declarative query. Users and the AI planner only ever produce this; SQL is compiled from it by the
 * query service after permission checks. dimensions are columns of the metric's table, "table.column" for a safely
 * joined table, or "time" for the metric's date column bucketed by timeGrain (day, month, year).
 */
public record QuerySpec(String metric, List<String> dimensions, List<Filter> filters, String timeGrain, DateRange dateRange,
                        String sortBy, String sortDir, Integer limit) {
    /** op is one of EQ, NE, IN, GT, GTE, LT, LTE, CONTAINS. */
    public record Filter(String column, String op, List<String> values) {}
    /** from inclusive, to exclusive; ISO dates (yyyy-MM-dd). */
    public record DateRange(String from, String to) {}

    public QuerySpec {
        dimensions = dimensions == null ? List.of() : dimensions;
        filters = filters == null ? List.of() : filters;
    }
}
