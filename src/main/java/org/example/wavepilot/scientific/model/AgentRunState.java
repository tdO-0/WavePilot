package org.example.wavepilot.scientific.model;

public enum AgentRunState {
    CREATED,
    PLANNING,
    RETRIEVING,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    REPLANNING,
    SUCCEEDED,
    FAILED,
    BUDGET_EXHAUSTED,
    TIMED_OUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == BUDGET_EXHAUSTED
                || this == TIMED_OUT || this == CANCELLED;
    }
}
