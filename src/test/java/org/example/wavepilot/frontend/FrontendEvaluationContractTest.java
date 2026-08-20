package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workbench must run offline evals and surface metrics plus failing cases. */
class FrontendEvaluationContractTest {

    @Test
    void evalEntryPointsExistForBothStubModels() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("runEval('stub-v1')"), "reference eval entry missing");
        assertTrue(app.contains("runEval('stub-v2')"), "regressed eval entry missing");
        assertTrue(app.contains("/evaluations/run"), "eval run endpoint missing");
        assertTrue(app.contains("datasetName: 'default'"), "default dataset missing");
    }

    @Test
    void metricsAreDisplayedWithComputedValuesAndCounts() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("metric.numerator"), "metric numerator missing");
        assertTrue(app.contains("metric.denominator"), "metric denominator missing");
        assertTrue(app.contains("metric.metricName"), "metric name missing");
    }

    @Test
    void failedCasesAreListedIndividuallyWithReasons() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("error-text"), "failed cases must use the error style");
        assertTrue(app.contains("result.failureReason"), "failure reason display missing");
        assertTrue(app.contains("result.caseType"), "case type display missing");
        assertTrue(app.contains("!result.passed"), "failed case filter missing");
    }

    @Test
    void baselineCandidateComparisonIsSupported() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("compareEvals"), "compare handler missing");
        assertTrue(app.contains("/evaluations/compare"), "compare endpoint missing");
        assertTrue(app.contains("comparison.releaseAllowed"), "release decision display missing");
        assertTrue(app.contains("regressedCaseIds"), "regressed cases display missing");
        assertTrue(app.contains("newlyPassedCaseIds"), "newly passed cases display missing");
    }

    @Test
    void evalFailuresAreShownAsErrors() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("Eval 运行失败"), "eval failure hint missing");
        assertTrue(app.contains("evalError"), "eval error area missing");
        assertTrue(app.contains("Eval Case 失败") || app.contains("failed-case"),
                "eval case failures must be visibly rendered");
    }
}
