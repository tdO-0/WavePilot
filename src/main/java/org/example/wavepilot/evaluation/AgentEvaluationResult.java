package org.example.wavepilot.evaluation;

public record AgentEvaluationResult(
        AgentEvaluationDimension dimension,
        boolean passed,
        String evidence) {
}
