package org.example.wavepilot.scientific.model;

import java.time.Instant;

public record ExecutionRecord(
        String executionId,
        String idempotencyKey,
        int iteration,
        String stepId,
        ExecutionStatus status,
        String jobId,
        int retryCount,
        boolean reused,
        String error,
        Instant startedAt,
        Instant completedAt) {
    public ExecutionRecord with(ExecutionStatus next, String nextJobId, int retries,
                                boolean wasReused, String nextError) {
        return new ExecutionRecord(executionId, idempotencyKey, iteration, stepId, next,
                nextJobId, retries, wasReused, nextError, startedAt,
                next == ExecutionStatus.COMPLETED || next == ExecutionStatus.FAILED
                        || next == ExecutionStatus.TIMED_OUT ? Instant.now() : null);
    }
}
