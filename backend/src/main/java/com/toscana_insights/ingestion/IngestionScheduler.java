package com.toscana_insights.ingestion;

import com.toscana_insights.datasource.DataSourceConnection;
import com.toscana_insights.datasource.DataSourceRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs due REST and dump sources. Each run is bounded: capped pages and bytes, 3 fetch attempts, then the next interval retries. */
@Component
@ConditionalOnProperty(name = "app.ingestion.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class IngestionScheduler {
    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);
    private final DataSourceRepository sources;
    private final IngestionService ingestion;

    public IngestionScheduler(DataSourceRepository sources, IngestionService ingestion) { this.sources = sources; this.ingestion = ingestion; }

    @Scheduled(fixedDelayString = "${app.ingestion.poll-ms:60000}", initialDelayString = "${app.ingestion.poll-ms:60000}")
    public void runDueSources() {
        for (DataSourceConnection source : sources.findByScheduleMinutesNotNullAndNextRunAtBefore(Instant.now())) {
            if (source.isPaused() || !(source.getType().equals("REST_API") || source.getType().equals("FILE_DUMP"))) continue;
            try { ingestion.runNow(source, "SCHEDULED", "system"); }
            catch (RuntimeException e) { log.warn("Scheduled run skipped for source {}: {}", source.getId(), e.getMessage()); }
        }
    }
}
