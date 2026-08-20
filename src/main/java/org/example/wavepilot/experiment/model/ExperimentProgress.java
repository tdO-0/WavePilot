package org.example.wavepilot.experiment.model;

import java.time.Instant;

public record ExperimentProgress(
        String jobId,
        ExperimentStatus status,
        int progress,
        String currentStage,
        long completedRuns,
        long totalRuns,
        String message,
        Instant timestamp) {

    public ExperimentProgress {
        progress = Math.max(0, Math.min(100, progress));
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
