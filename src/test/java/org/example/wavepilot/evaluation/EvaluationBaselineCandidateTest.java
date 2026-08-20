package org.example.wavepilot.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationBaselineCandidateTest {

    @TempDir Path root;

    @Test
    void comparisonReportsPerMetricDeltasAndCaseLevelRegressions() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun baseline = stack.evaluationService().run("default", "stub-v1");
        EvaluationRun candidate = stack.evaluationService().run("default", "stub-v2");

        EvaluationComparison comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());

        assertEquals(baseline.evaluationId(), comparison.baselineEvaluationId());
        assertEquals(candidate.evaluationId(), comparison.candidateEvaluationId());
        assertEquals("default", comparison.datasetName());

        Map<String, EvaluationComparison.MetricDelta> deltas =
                comparison.metricDeltas().stream().collect(Collectors.toMap(
                        EvaluationComparison.MetricDelta::metricName, delta -> delta));
        assertEquals(11, deltas.size());
        assertEquals(-0.5, deltas.get("toolSelectionAccuracy").delta(), 1.0e-12);
        assertEquals(-0.5, deltas.get("missingParameterDetectionRate").delta(), 1.0e-12);
        assertEquals(-2.0 / 24.0, deltas.get("overallTaskCompletionRate").delta(), 1.0e-12);

        assertEquals(List.of("C-004", "C-009"), comparison.regressedCaseIds());
        assertTrue(comparison.newlyPassedCaseIds().isEmpty());
        assertFalse(comparison.releaseAllowed());
        assertTrue(comparison.message().contains("C-009") || comparison.message().contains("regressed"));
    }

    @Test
    void everyMetricDeltaIsPairedByNameBetweenTheTwoRuns() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun baseline = stack.evaluationService().run("default", "stub-v1");
        EvaluationRun candidate = stack.evaluationService().run("default", "stub-v2");
        EvaluationComparison comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());

        Map<String, EvaluationMetric> baselineByName = baseline.metrics().stream()
                .collect(Collectors.toMap(EvaluationMetric::metricName, metric -> metric));
        Map<String, EvaluationMetric> candidateByName = candidate.metrics().stream()
                .collect(Collectors.toMap(EvaluationMetric::metricName, metric -> metric));
        for (EvaluationComparison.MetricDelta delta : comparison.metricDeltas()) {
            assertEquals(candidateByName.get(delta.metricName()).value()
                    - baselineByName.get(delta.metricName()).value(), delta.delta(), 1.0e-12);
        }
    }

    @Test
    void comparingRunsOnDifferentDatasetsIsRejected() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun runA = new EvaluationRun("EVAL-A", "default", "stub-v1", "SUCCEEDED",
                java.time.Instant.now(), java.time.Instant.now(), List.of(), List.of());
        EvaluationRun runB = new EvaluationRun("EVAL-B", "other-dataset", "stub-v1", "SUCCEEDED",
                java.time.Instant.now(), java.time.Instant.now(), List.of(), List.of());
        stack.repository().save(runA);
        stack.repository().save(runB);

        EvaluationException exception = org.junit.jupiter.api.Assertions.assertThrows(
                EvaluationException.class, () -> stack.evaluationService().compare("EVAL-A", "EVAL-B"));
        assertTrue(exception.getMessage().contains("same dataset"));
    }
}
