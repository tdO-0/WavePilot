package org.example.wavepilot.report;

import org.example.wavepilot.experiment.model.ExperimentStatus;

import java.time.Instant;
import java.util.List;

/** Phase 5 report contract. Phase 0-2 only establishes citation-safe data structures. */
public record ExperimentReport(
        String jobId,
        ExperimentStatus status,
        String summary,
        List<Conclusion> conclusions,
        Instant createdAt) {

    public ExperimentReport {
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public record Conclusion(
            String conclusion,
            Number value,
            String artifactId,
            String fieldName,
            String rowReference) {
    }
}
