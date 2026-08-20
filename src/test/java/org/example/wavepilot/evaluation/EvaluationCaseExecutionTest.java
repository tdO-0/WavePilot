package org.example.wavepilot.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationCaseExecutionTest {

    @TempDir Path root;

    @Test
    void referenceModelPassesEveryCaseWithFullPerCaseRecords() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        assertEquals("SUCCEEDED", run.status());
        assertEquals(24, run.results().size());
        assertEquals(24, run.passedCases());
        for (EvaluationCaseResult result : run.results()) {
            assertNotNull(result.caseId());
            assertNotNull(result.actualResult());
            assertTrue(!result.actualResult().isBlank(), result.caseId() + " must record an actual outcome");
            assertNull(result.failureReason(), result.caseId() + " must have no failure reason");
            assertTrue(result.passed(), result.caseId() + " must pass under the reference model");
        }
    }

    @Test
    void everyDatasetCaseIsExecutedExactlyOnce() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        List<String> executed = run.results().stream().map(EvaluationCaseResult::caseId).toList();
        List<String> expected = stack.dataset().require("default")
                .stream().map(EvaluationCase::caseId).toList();
        assertEquals(expected, executed);
    }

    @Test
    void platformCasesExerciseTheRealChain() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        EvaluationCaseResult job = result(run, "C-013");
        assertEquals("SUCCEEDED", job.actualResult());
        EvaluationCaseResult citation = result(run, "C-019");
        assertEquals("CITATIONS_VERIFIED", citation.actualResult());
        EvaluationCaseResult grounding = result(run, "C-021");
        assertEquals("GROUNDED", grounding.actualResult());
        EvaluationCaseResult replay = result(run, "C-023");
        assertEquals("REPRODUCIBLE", replay.actualResult());
    }

    private EvaluationCaseResult result(EvaluationRun run, String caseId) {
        return run.results().stream().filter(result -> result.caseId().equals(caseId))
                .findFirst().orElseThrow();
    }
}
