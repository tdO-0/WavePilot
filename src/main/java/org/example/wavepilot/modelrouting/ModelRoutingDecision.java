package org.example.wavepilot.modelrouting;

import java.time.Instant;

public record ModelRoutingDecision(
        ModelTaskType taskType,
        String route,
        boolean modelCall,
        String reason,
        Integer inputTokens,
        Integer outputTokens,
        Instant timestamp) {
    public ModelRoutingDecision {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
