package org.example.wavepilot.experiment.outbox;

/**
 * Append-only dispatch journal. Implementations must be idempotent per job: appending an
 * event for a job that already has one keeps the first event untouched. Only the MySQL
 * implementation exists; the api role with a non-MySQL repository falls back to the
 * synchronous publish path in {@code ExperimentService}.
 */
public interface OutboxEventStore {
    void append(String jobId);
}
