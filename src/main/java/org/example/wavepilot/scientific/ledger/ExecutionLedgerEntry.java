package org.example.wavepilot.scientific.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExecutionLedgerEntry(
        String executionId,
        String runId,
        String jobId,
        String experimentSpecFingerprint,
        ExecutionLedgerStatus currentStatus,
        List<LedgerArtifactReference> artifactReferences,
        Map<String, Object> summaryValues,
        int retryCount,
        Instant startedAt,
        Instant completedAt,
        String failureReason) {
    public ExecutionLedgerEntry {
        if (executionId == null || executionId.isBlank() || runId == null || runId.isBlank()
                || experimentSpecFingerprint == null || experimentSpecFingerprint.isBlank()
                || currentStatus == null || startedAt == null || retryCount < 0) {
            throw new IllegalArgumentException("execution ledger identity, fingerprint, status and start time are required");
        }
        artifactReferences = artifactReferences == null ? List.of() : List.copyOf(artifactReferences);
        summaryValues = summaryValues == null ? Map.of() : Map.copyOf(summaryValues);
    }

    public ExecutionLedgerEntry withStatus(ExecutionLedgerStatus status, String nextJobId,
                                           List<LedgerArtifactReference> artifacts,
                                           Map<String, Object> summary, int retries,
                                           String failure) {
        return new ExecutionLedgerEntry(executionId, runId, nextJobId,
                experimentSpecFingerprint, status, artifacts, summary, retries, startedAt,
                status == ExecutionLedgerStatus.COMPLETED || status == ExecutionLedgerStatus.FAILED
                        || status == ExecutionLedgerStatus.UNCERTAIN ? Instant.now() : null, failure);
    }
}
