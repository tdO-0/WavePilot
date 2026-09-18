package org.example.wavepilot.experiment.outbox;

import org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable dispatch payload. {@code messageId} is fixed when the event is created so that
 * every retry of the same event publishes the same logical message; delivery stays
 * at-least-once and the consumer's DB claim deduplicates.
 */
public record OutboxPayload(String jobId, String messageId, String executionId, Instant createdAt) {

    public static OutboxPayload forJob(String jobId) {
        return new OutboxPayload(jobId, UUID.randomUUID().toString(), "EXEC-" + jobId, Instant.now());
    }

    /** Re-validates every field through the message contract before publishing. */
    public ExperimentExecutionMessage toMessage() {
        return new ExperimentExecutionMessage(messageId, jobId, executionId, createdAt);
    }
}
