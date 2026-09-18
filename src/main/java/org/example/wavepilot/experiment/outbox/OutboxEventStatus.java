package org.example.wavepilot.experiment.outbox;

public enum OutboxEventStatus {
    /** Waiting for (or currently claimed by) a relay. */
    PENDING,
    /** Broker confirmed the publish; the event is never relayed again. */
    SENT
}
