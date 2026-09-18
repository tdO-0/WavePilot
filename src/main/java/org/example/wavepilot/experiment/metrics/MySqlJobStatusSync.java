package org.example.wavepilot.experiment.metrics;

import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.mysql.ExperimentJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Re-syncs the per-status gauges from the source of truth so a restart (or a transition
 * observed by another node) does not leave the gauges drifting. MySQL is the durable
 * source; non-MySQL roles keep their delta-only gauges.
 */
@Component
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "wavepilot.job-repository", havingValue = "mysql")
public class MySqlJobStatusSync {
    private static final Logger log = LoggerFactory.getLogger(MySqlJobStatusSync.class);

    private final ExperimentJobMapper mapper;
    private final JobExecutionMetrics metrics;

    public MySqlJobStatusSync(ExperimentJobMapper mapper, JobExecutionMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${wavepilot.metrics.status-sync-interval-ms:30000}")
    public void sync() {
        try {
            Map<ExperimentStatus, Long> counts = new EnumMap<>(ExperimentStatus.class);
            for (Map<String, Object> row : mapper.countByStatus()) {
                try {
                    counts.put(ExperimentStatus.valueOf(String.valueOf(row.get("status"))),
                            ((Number) row.get("cnt")).longValue());
                } catch (IllegalArgumentException unknown) {
                    log.warn("Unknown persisted status ignored in metrics sync: {}", row.get("status"));
                }
            }
            metrics.setStatusCounts(counts);
        } catch (RuntimeException e) {
            log.warn("Status gauge sync skipped: {}", e.getMessage());
        }
    }
}
