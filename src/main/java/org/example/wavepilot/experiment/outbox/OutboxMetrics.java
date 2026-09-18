package org.example.wavepilot.experiment.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Relay-side metrics; all names carry the wavepilot_ prefix. No jobId/userId tags. */
@Component
public class OutboxMetrics {
    private final Counter published;
    private final Counter publishFailures;
    private final Counter retries;
    private final AtomicLong pending = new AtomicLong();

    public OutboxMetrics(MeterRegistry registry) {
        this.published = Counter.builder("wavepilot.outbox.published")
                .description("Outbox events whose RabbitMQ publish was broker-confirmed").register(registry);
        this.publishFailures = Counter.builder("wavepilot.outbox.publish.failures")
                .description("Outbox publish attempts that failed and will be retried").register(registry);
        this.retries = Counter.builder("wavepilot.outbox.retries")
                .description("Outbox events claimed again after at least one failed attempt").register(registry);
        Gauge.builder("wavepilot.outbox.pending", pending, AtomicLong::get)
                .description("Outbox events not yet broker-confirmed").register(registry);
    }

    public void published() { published.increment(); }
    public void publishFailed() { publishFailures.increment(); }
    public void retried() { retries.increment(); }
    public void refreshPending(long count) { pending.set(count); }
}
