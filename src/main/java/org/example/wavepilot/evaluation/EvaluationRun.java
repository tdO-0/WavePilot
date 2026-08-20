package org.example.wavepilot.evaluation;

import java.time.Instant;
import java.util.List;

/** One executed evaluation run over a fixed dataset with a named model. */
public record EvaluationRun(
        String evaluationId,
        String datasetName,
        String modelName,
        String status,
        Instant startedAt,
        Instant completedAt,
        List<EvaluationCaseResult> results,
        List<EvaluationMetric> metrics) {

    public EvaluationRun {
        results = List.copyOf(results);
        metrics = List.copyOf(metrics);
    }

    public long passedCases() {
        return results.stream().filter(EvaluationCaseResult::passed).count();
    }
}
