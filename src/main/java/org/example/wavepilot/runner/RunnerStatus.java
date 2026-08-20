package org.example.wavepilot.runner;

import java.time.Instant;

public record RunnerStatus(
        String externalJobId,
        State state,
        int progress,
        long completedRuns,
        long totalRuns,
        String message,
        Integer exitCode,
        Instant timestamp) {

    public enum State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    public boolean terminal() {
        return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
    }
}
