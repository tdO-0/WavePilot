package org.example.wavepilot.experiment.model;

import java.time.Instant;
import java.util.List;

public record ExperimentPlan(
        String planId,
        Object spec,
        String experimentTemplateVersion,
        long totalRuns,
        List<String> stages,
        Instant createdAt) {

    public ExperimentPlan {
        stages = stages == null ? List.of() : List.copyOf(stages);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
