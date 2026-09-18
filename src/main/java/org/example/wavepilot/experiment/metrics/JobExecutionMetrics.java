package org.example.wavepilot.experiment.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Job lifecycle metrics, all with the wavepilot_ prefix and no high-cardinality tags
 * (jobId/userId never become tags). Per-status gauges are delta-maintained on every
 * transition; the MySQL backend additionally re-syncs absolute counts periodically
 * (see MySqlJobStatusSync) so restarts do not drift the gauges.
 */
@Component
public class JobExecutionMetrics {
    private final Counter created;
    private final Map<ExperimentStatus, Counter> completed = new EnumMap<>(ExperimentStatus.class);
    private final Map<ExperimentStatus, AtomicLong> current = new EnumMap<>(ExperimentStatus.class);
    private final Timer queueTime;
    private final Timer executionTime;

    public JobExecutionMetrics(MeterRegistry registry) {
        this.created = Counter.builder("wavepilot.jobs.created")
                .description("Experiment jobs created").register(registry);
        for (ExperimentStatus status : ExperimentStatus.values()) {
            if (status.isTerminal()) {
                completed.put(status, Counter.builder("wavepilot.jobs.completed")
                        .description("Experiment jobs reaching a terminal status")
                        .tag("status", status.name().toLowerCase()).register(registry));
            }
            AtomicLong counter = new AtomicLong();
            current.put(status, counter);
            Gauge.builder("wavepilot.jobs.current", counter, AtomicLong::get)
                    .description("Experiment jobs currently in a status")
                    .tag("status", status.name().toLowerCase()).register(registry);
        }
        this.queueTime = Timer.builder("wavepilot.jobs.queue.time")
                .description("Time from job creation to worker claim").register(registry);
        this.executionTime = Timer.builder("wavepilot.jobs.execution.time")
                .description("Time from worker claim to a terminal status").register(registry);
    }

    public void jobCreated() {
        created.increment();
        current.get(ExperimentStatus.CREATED).incrementAndGet();
    }

    public void statusChanged(ExperimentStatus from, ExperimentStatus to) {
        if (from != null) current.get(from).decrementAndGet();
        current.get(to).incrementAndGet();
        Counter terminal = completed.get(to);
        if (terminal != null) terminal.increment();
    }

    public void recordQueueTime(Duration queueDuration) {
        queueTime.record(queueDuration);
    }

    public void recordExecutionTime(Duration executionDuration) {
        executionTime.record(executionDuration);
    }

    /** Absolute re-sync for the MySQL backend; called by MySqlJobStatusSync. */
    public void setStatusCounts(Map<ExperimentStatus, Long> counts) {
        for (ExperimentStatus status : ExperimentStatus.values()) {
            current.get(status).set(counts.getOrDefault(status, 0L));
        }
    }
}
