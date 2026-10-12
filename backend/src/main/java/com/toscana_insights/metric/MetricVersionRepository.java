package com.toscana_insights.metric;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetricVersionRepository extends JpaRepository<MetricVersion, UUID> {
    List<MetricVersion> findByMetricIdOrderByCreatedAtAsc(UUID metricId);
}
