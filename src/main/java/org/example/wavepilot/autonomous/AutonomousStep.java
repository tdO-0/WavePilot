package org.example.wavepilot.autonomous;

import java.time.Instant;

/** One timeline entry of an autonomous session: model reasoning, tool call, tool result or system note. */
public record AutonomousStep(
        String stepId,
        String role,
        String message,
        String toolName,
        String toolResult,
        AutonomousStatus status,
        Instant timestamp) {
}
