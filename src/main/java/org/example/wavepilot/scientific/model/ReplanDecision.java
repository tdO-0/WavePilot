package org.example.wavepilot.scientific.model;

import org.example.wavepilot.experiment.model.ExperimentSpec;

import java.time.Instant;

public record ReplanDecision(
        int afterIteration,
        boolean replan,
        ExperimentSpec nextSpec,
        String reason,
        boolean terminal,
        Instant decidedAt) {
    public ReplanDecision {
        reason = reason == null ? "" : reason;
        decidedAt = decidedAt == null ? Instant.now() : decidedAt;
    }
}
