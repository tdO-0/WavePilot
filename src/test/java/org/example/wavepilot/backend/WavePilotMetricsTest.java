package org.example.wavepilot.backend;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.wavepilot.experiment.lease.LeaseMetrics;
import org.example.wavepilot.experiment.metrics.JobExecutionMetrics;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.outbox.OutboxMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WavePilotMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void everyMetricCarriesTheWavepilotPrefixAndKeepsLowCardinalityTags() {
        new JobExecutionMetrics(registry);
        new LeaseMetrics(registry);
        new OutboxMetrics(registry);

        var meters = registry.getMeters().stream()
                .map(meter -> meter.getId().getName()).toList();
        assertFalse(meters.isEmpty());
        for (String name : meters) assertTrue(name.startsWith("wavepilot."), "unprefixed metric: " + name);
        // Required families are present.
        for (String expected : new String[] {
                "wavepilot.jobs.created", "wavepilot.jobs.completed", "wavepilot.jobs.current",
                "wavepilot.jobs.queue.time", "wavepilot.jobs.execution.time",
                "wavepilot.outbox.pending", "wavepilot.outbox.published",
                "wavepilot.outbox.publish.failures", "wavepilot.outbox.retries",
                "wavepilot.lease.expired", "wavepilot.lease.claims"}) {
            assertTrue(meters.contains(expected), "missing metric family: " + expected);
        }
        // No jobId/userId-style high-cardinality tags anywhere.
        for (var meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertFalse(tag.getKey().contains("job"), "high-cardinality tag: " + tag.getKey());
                assertFalse(tag.getKey().contains("user"), "high-cardinality tag: " + tag.getKey());
            }
        }
    }

    @Test
    void jobGaugesFollowTransitionsAndTerminalCountersIncrement() {
        var metrics = new JobExecutionMetrics(registry);
        metrics.jobCreated();
        metrics.statusChanged(ExperimentStatus.CREATED, ExperimentStatus.VALIDATED);
        metrics.statusChanged(ExperimentStatus.VALIDATED, ExperimentStatus.QUEUED);
        metrics.statusChanged(ExperimentStatus.QUEUED, ExperimentStatus.RUNNING);
        metrics.statusChanged(ExperimentStatus.RUNNING, ExperimentStatus.SUCCEEDED);

        assertEquals(1.0, registry.get("wavepilot.jobs.current").tag("status", "succeeded").gauge().value());
        assertEquals(1.0, registry.get("wavepilot.jobs.completed").tag("status", "succeeded").counter().count());
        assertEquals(0.0, registry.get("wavepilot.jobs.current").tag("status", "running").gauge().value());
        metrics.recordQueueTime(Duration.ofMillis(50));
        metrics.recordExecutionTime(Duration.ofSeconds(2));
        assertEquals(1, registry.get("wavepilot.jobs.queue.time").timer().count());
    }
}
