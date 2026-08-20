package org.example.wavepilot.experiment.model;

public enum ExperimentStatus {
    CREATED,
    VALIDATED,
    QUEUED,
    RUNNING,
    VALIDATING_RESULT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
