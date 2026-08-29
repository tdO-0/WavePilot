package org.example.wavepilot.evaluation;

public record AgentEvaluationTelemetry(
        double planValidity,
        double loopTerminationRate,
        double invalidToolCallRate,
        double invalidExperimentSpecRate,
        double replanSuccessRate,
        double artifactGroundingSuccess,
        double recoverySuccess,
        double duplicateExecutionRate,
        double retrievalQuality,
        double citationValidity,
        long totalLatencyMillis,
        int modelCalls) {
}
