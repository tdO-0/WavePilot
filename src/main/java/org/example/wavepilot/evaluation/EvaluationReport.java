package org.example.wavepilot.evaluation;

import java.time.Instant;
import java.util.List;

/** Summary report of an evaluation run; registered as an EVAL_REPORT artifact. */
public record EvaluationReport(
        String evaluationId,
        String datasetName,
        String modelName,
        Instant createdAt,
        String status,
        List<EvaluationMetric> metrics,
        List<EvaluationCaseResult> results) {

    public EvaluationReport {
        metrics = List.copyOf(metrics);
        results = List.copyOf(results);
    }

    public long passedCases() {
        return results.stream().filter(EvaluationCaseResult::passed).count();
    }

    public long totalCases() {
        return results.size();
    }
}
