package org.example.wavepilot.scientific.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Observation(
        int iteration,
        String executionId,
        String jobId,
        Map<String, Object> metrics,
        List<ArtifactSnapshot> artifacts,
        boolean deterministicResultValidationPassed,
        Instant observedAt) {
    public Observation {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
