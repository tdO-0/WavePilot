package org.example.wavepilot.scientific.model;

import java.time.Duration;

public record RunBudget(
        int maxIterations,
        int maxExperiments,
        int maxModelCalls,
        long maxTokens,
        int maxRetries,
        Duration timeout) {
    public RunBudget {
        if (maxIterations < 1 || maxExperiments < 1 || maxModelCalls < 0 || maxTokens < 0
                || maxRetries < 0) {
            throw new IllegalArgumentException("run budget values are out of range");
        }
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    public static RunBudget offlineDefaults() {
        return new RunBudget(4, 4, 0, 0, 1, Duration.ofMinutes(2));
    }
}
