package org.example.wavepilot.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationMetricCalculationTest {

    private static final List<String> REQUIRED_METRICS = List.of(
            "specParseAccuracy", "missingParameterDetectionRate", "invalidParameterBlockRate",
            "toolSelectionAccuracy", "forbiddenToolBlockRate", "jobSubmissionSuccessRate",
            "artifactCitationConsistencyRate", "reportGroundingRate", "replayConsistencyRate",
            "overallTaskCompletionRate");

    @TempDir Path root;

    @Test
    void everyRequiredMetricIsComputedFromTheActualResults() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        Map<String, EvaluationMetric> metrics = byName(run.metrics());
        for (String required : REQUIRED_METRICS) {
            EvaluationMetric metric = metrics.get(required);
            assertTrue(metric != null, "missing metric: " + required);
            long numerator = run.results().stream()
                    .filter(result -> matches(required, result.caseType())).filter(EvaluationCaseResult::passed).count();
            long denominator = run.results().stream()
                    .filter(result -> matches(required, result.caseType())).count();
            assertEquals(numerator, metric.numerator());
            assertEquals(denominator, metric.denominator());
            assertEquals(ratio(numerator, denominator), metric.value(), 1.0e-12,
                    "metric " + required + " must equal numerator/denominator");
        }
    }

    @Test
    void perTypeDenominatorsMatchTheDataset() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");
        Map<String, EvaluationMetric> metrics = byName(run.metrics());
        assertEquals(2, metrics.get("specParseAccuracy").denominator());
        assertEquals(2, metrics.get("missingParameterDetectionRate").denominator());
        assertEquals(2, metrics.get("invalidParameterBlockRate").denominator());
        assertEquals(2, metrics.get("toolSelectionAccuracy").denominator());
        assertEquals(2, metrics.get("forbiddenToolBlockRate").denominator());
        assertEquals(2, metrics.get("jobSubmissionSuccessRate").denominator());
        assertEquals(2, metrics.get("artifactCitationConsistencyRate").denominator());
        assertEquals(2, metrics.get("reportGroundingRate").denominator());
        assertEquals(2, metrics.get("replayConsistencyRate").denominator());
        assertEquals(24, metrics.get("overallTaskCompletionRate").denominator());
    }

    private boolean matches(String metricName, EvaluationCaseType caseType) {
        return switch (metricName) {
            case "specParseAccuracy" -> caseType == EvaluationCaseType.COMPLETE_SPEC;
            case "missingParameterDetectionRate" -> caseType == EvaluationCaseType.MISSING_PARAMETER;
            case "invalidParameterBlockRate" -> caseType == EvaluationCaseType.INVALID_PARAMETER;
            case "toolSelectionAccuracy" -> caseType == EvaluationCaseType.TOOL_SELECTION;
            case "forbiddenToolBlockRate" -> caseType == EvaluationCaseType.TOOL_SECURITY;
            case "jobSubmissionSuccessRate" -> caseType == EvaluationCaseType.JOB_SUBMISSION;
            case "artifactCitationConsistencyRate" -> caseType == EvaluationCaseType.ARTIFACT_CITATION;
            case "reportGroundingRate" -> caseType == EvaluationCaseType.REPORT_GROUNDING;
            case "replayConsistencyRate" -> caseType == EvaluationCaseType.REPLAY_CONSISTENCY;
            default -> true;
        };
    }

    private Map<String, EvaluationMetric> byName(List<EvaluationMetric> metrics) {
        return metrics.stream().collect(Collectors.toMap(EvaluationMetric::metricName, metric -> metric));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
