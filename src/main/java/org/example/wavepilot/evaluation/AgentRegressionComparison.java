package org.example.wavepilot.evaluation;

import java.time.Instant;
import java.util.List;

public record AgentRegressionComparison(
        String baselineEvaluationId,
        String candidateEvaluationId,
        List<AgentEvaluationDimension> regressedDimensions,
        List<AgentEvaluationDimension> improvedDimensions,
        double successRateDelta,
        boolean releaseAllowed,
        Instant comparedAt) {
    public AgentRegressionComparison {
        regressedDimensions = List.copyOf(regressedDimensions);
        improvedDimensions = List.copyOf(improvedDimensions);
    }
}
