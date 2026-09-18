package org.example.wavepilot.experiment.model;

public enum ExperimentStatus {
    CREATED,
    VALIDATED,
    QUEUED,
    RUNNING,
    VALIDATING_RESULT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    /**
     * Terminal: a worker submitted the job to an external runner but its lease expired
     * before the outcome could be verified. Recovery deliberately refuses to re-run it
     * (the external side effect may exist); a human decides what happened.
     */
    UNCERTAIN;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNCERTAIN;
    }
}
