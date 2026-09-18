package org.example.wavepilot.experiment.outbox;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.messaging.ExperimentMessagePublisher;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventMapper;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Publishes PENDING outbox events on the api role only (the worker never owns a relay, and
 * standalone does not use RabbitMQ). Multiple api instances may run concurrently: each row
 * is claimed by a conditional UPDATE that pushes {@code nextRetryAt} out by
 * {@link OutboxProperties#claimLease()}, so a claimer that dies mid-publish only delays the
 * event by one claim lease instead of losing it. A publish is broker-confirmed (publisher
 * confirm + mandatory/returned check) before the event becomes SENT; SENT rows are never
 * claimed again. Failures keep the row PENDING with a capped exponential backoff, and
 * pending rows survive restarts because the journal lives in MySQL.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@ConditionalOnExpression("'${wavepilot.node-role:standalone}' == 'api' and '${wavepilot.job-repository:in-memory}' == 'mysql'")
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventMapper mapper;
    private final ExperimentMessagePublisher publisher;
    private final ObjectMapper json;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;

    public OutboxRelay(OutboxEventMapper mapper, ExperimentMessagePublisher publisher,
                       ObjectMapper json, OutboxProperties properties, OutboxMetrics metrics) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.json = json;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${wavepilot.outbox.relay-interval-ms:1000}")
    public void relayBatch() {
        if (!properties.relayEnabled()) return;
        Instant now = Instant.now();
        metrics.refreshPending(mapper.selectCount(new QueryWrapper<OutboxEventRow>().eq("status", "PENDING")));
        List<OutboxEventRow> due = mapper.selectList(new QueryWrapper<OutboxEventRow>()
                .eq("status", "PENDING").le("next_retry_at", now)
                .orderByAsc("created_at").last("LIMIT " + properties.batchSize()));
        for (OutboxEventRow row : due) {
            try {
                deliver(row, now);
            } catch (RuntimeException failure) {
                log.error("Outbox relay failed for event {} (job {})", row.id, row.jobId, failure);
                metrics.publishFailed();
                retryLater(row, now);
            }
        }
    }

    private void deliver(OutboxEventRow row, Instant now) {
        // Claim before publishing: another relay must not publish the same row in parallel.
        if (mapper.claim(row.id, row.version, now,
                now.plus(properties.claimLease())) != 1) return;
        row.version++;
        if (row.attemptCount > 0) metrics.retried();
        OutboxPayload payload;
        try {
            payload = json.readValue(row.payloadJson, OutboxPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt outbox payload for " + row.jobId, e);
        }
        publisher.publish(payload.toMessage()); // waits for confirm; returned/mandatory handled inside
        if (mapper.markSent(row.id, row.version, Instant.now()) != 1) {
            // Another relay re-claimed after our claim lease expired; its delivery stands.
            log.warn("Event {} was re-claimed by another relay; duplicate delivery is tolerated", row.id);
        }
        metrics.published();
    }

    private void retryLater(OutboxEventRow row, Instant now) {
        // Row may have lost the claim (version mismatch): the new claimant owns the retry.
        if (mapper.markFailed(row.id, row.version, now.plus(backoff(row.attemptCount))) == 1)
            log.warn("Outbox event {} (job {}) will retry after {} attempts",
                    row.id, row.jobId, row.attemptCount + 1);
    }

    /** Capped exponential backoff: base * 2^attempts, never beyond the configured cap. */
    public static Duration backoff(int attempts, Duration base, Duration cap) {
        long millis = base.toMillis();
        for (int i = 0; i < attempts && millis < cap.toMillis(); i++) millis = Math.min(cap.toMillis(), millis * 2);
        return Duration.ofMillis(millis);
    }

    private Duration backoff(int attempts) {
        return backoff(attempts, properties.backoffBase(), properties.backoffCap());
    }
}
