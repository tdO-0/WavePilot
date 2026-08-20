package org.example.wavepilot.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationNoHardcodedMetricTest {

    @TempDir Path root;

    @Test
    void metricsChangeWhenExecutionResultsChange() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun reference = stack.evaluationService().run("default", "stub-v1");
        EvaluationRun regressed = stack.evaluationService().run("default", "stub-v2");

        Map<String, EvaluationMetric> referenceMetrics = byName(reference.metrics());
        Map<String, EvaluationMetric> regressedMetrics = byName(regressed.metrics());

        // Identical execution results must yield identical metrics...
        assertEquals(1.0, referenceMetrics.get("specParseAccuracy").value(), 1.0e-12);
        assertEquals(1.0, referenceMetrics.get("invalidParameterBlockRate").value(), 1.0e-12);
        // ...and different results must move the metrics, proving they are not hardcoded.
        assertNotEquals(referenceMetrics.get("toolSelectionAccuracy").value(),
                regressedMetrics.get("toolSelectionAccuracy").value());
        assertNotEquals(referenceMetrics.get("missingParameterDetectionRate").value(),
                regressedMetrics.get("missingParameterDetectionRate").value());
        assertNotEquals(referenceMetrics.get("overallTaskCompletionRate").value(),
                regressedMetrics.get("overallTaskCompletionRate").value());

        assertEquals(0.5, regressedMetrics.get("toolSelectionAccuracy").value(), 1.0e-12);
        assertEquals(0.5, regressedMetrics.get("missingParameterDetectionRate").value(), 1.0e-12);
        assertEquals(22.0 / 24.0, regressedMetrics.get("overallTaskCompletionRate").value(), 1.0e-12);
    }

    @Test
    void computedValuesMatchRecomputationFromTheStoredResults() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun regressed = stack.evaluationService().run("default", "stub-v2");

        long passed = regressed.results().stream().filter(EvaluationCaseResult::passed).count();
        EvaluationMetric overall = byName(regressed.metrics()).get("overallTaskCompletionRate");
        assertEquals((double) passed / regressed.results().size(), overall.value(), 1.0e-12);
        assertTrue(passed < regressed.results().size(),
                "the regressed stub must fail at least one case for a meaningful metric");
    }

    private Map<String, EvaluationMetric> byName(java.util.List<EvaluationMetric> metrics) {
        return metrics.stream().collect(Collectors.toMap(EvaluationMetric::metricName, metric -> metric));
    }
}
