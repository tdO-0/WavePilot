package org.example.wavepilot.scientific.model;

import java.time.Instant;
import java.util.List;

public record ScientificExperimentPlan(
        String planId,
        int iteration,
        List<ExperimentPlanStep> steps,
        Instant createdAt) {
    public ScientificExperimentPlan {
        if (planId == null || planId.isBlank()) throw new IllegalArgumentException("planId is required");
        if (iteration < 1) throw new IllegalArgumentException("iteration must be >= 1");
        steps = steps == null ? List.of() : List.copyOf(steps);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (steps.isEmpty()) throw new IllegalArgumentException("plan steps are required");
    }
}
