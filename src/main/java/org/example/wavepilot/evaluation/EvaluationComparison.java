package org.example.wavepilot.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * Paired comparison of two runs over the same dataset. Every metric is compared case-by-case;
 * regressed and newly passed cases are listed explicitly so a score change can never hide a
 * per-case regression.
 */
public record EvaluationComparison(
        String baselineEvaluationId,
        String candidateEvaluationId,
        String datasetName,
        List<MetricDelta> metricDeltas,
        List<String> regressedCaseIds,
        List<String> newlyPassedCaseIds,
        boolean releaseAllowed,
        String message,
        Instant createdAt) {

    public EvaluationComparison {
        metricDeltas = List.copyOf(metricDeltas);
        regressedCaseIds = List.copyOf(regressedCaseIds);
        newlyPassedCaseIds = List.copyOf(newlyPassedCaseIds);
    }

    public record MetricDelta(String metricName, double baselineValue, double candidateValue, double delta) { }
}
