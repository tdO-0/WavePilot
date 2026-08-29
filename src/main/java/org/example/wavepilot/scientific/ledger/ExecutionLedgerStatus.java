package org.example.wavepilot.scientific.ledger;

public enum ExecutionLedgerStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    /** Side effect may have happened, but completion cannot be proven after restart. */
    UNCERTAIN
}
