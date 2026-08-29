package org.example.wavepilot.evaluation;

import java.time.Instant;
import java.util.List;

public record AgentRegressionEvaluationReport(
        String evaluationId,
        String agentRunId,
        String retrievalEvaluationId,
        String replayId,
        List<AgentEvaluationResult> results,
        long passed,
        int total,
        double successRate,
        Instant evaluatedAt,
        String disclosure) {
    public AgentRegressionEvaluationReport {
        results = List.copyOf(results);
    }
}
