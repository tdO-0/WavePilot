package org.example.wavepilot.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRegressionDetectionTest {

    @TempDir Path root;

    @Test
    void degradedCandidateIsFlaggedWithRegressedCases() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun baseline = stack.evaluationService().run("default", "stub-v1");
        EvaluationRun candidate = stack.evaluationService().run("default", "stub-v2");

        EvaluationComparison comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());

        assertEquals(List.of("C-004", "C-009"), comparison.regressedCaseIds());
        assertTrue(comparison.newlyPassedCaseIds().isEmpty());
        assertFalse(comparison.releaseAllowed(), "a regressed candidate must not be releasable");

        // Per-case evidence is preserved: the two failing candidate cases carry their reasons.
        for (String caseId : comparison.regressedCaseIds()) {
            EvaluationCaseResult result = candidate.results().stream()
                    .filter(item -> item.caseId().equals(caseId)).findFirst().orElseThrow();
            assertFalse(result.passed());
            assertTrue(result.failureReason() != null && !result.failureReason().isBlank(),
                    caseId + " must record its failure reason");
        }
    }

    @Test
    void improvedCandidateListsNewlyPassedCasesAndAllowsRelease() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun baseline = stack.evaluationService().run("default", "stub-v2");
        EvaluationRun candidate = stack.evaluationService().run("default", "stub-v1");

        EvaluationComparison comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());

        assertEquals(List.of("C-004", "C-009"), comparison.newlyPassedCaseIds());
        assertTrue(comparison.regressedCaseIds().isEmpty());
        assertTrue(comparison.releaseAllowed(), "an improved candidate with no regressions may release");
        assertTrue(comparison.metricDeltas().stream()
                .allMatch(delta -> delta.delta() >= -1.0e-12), "no metric may degrade");
    }

    @Test
    void overallScoreAloneWouldHideTheRegressionButCaseResultsDoNot() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun baseline = stack.evaluationService().run("default", "stub-v1");
        EvaluationRun candidate = stack.evaluationService().run("default", "stub-v2");

        EvaluationComparison comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());

        // The overall rate only drops from 24/24 to 22/24; per-case results are what identify
        // the regressed tool-selection and missing-parameter behavior.
        assertEquals(2, comparison.regressedCaseIds().size());
        assertEquals(0.9166666666666666, candidate.metrics().stream()
                .filter(metric -> metric.metricName().equals("overallTaskCompletionRate"))
                .findFirst().orElseThrow().value(), 1.0e-12);
        assertEquals(2, candidate.results().stream().filter(result -> !result.passed()).count());
    }
}
