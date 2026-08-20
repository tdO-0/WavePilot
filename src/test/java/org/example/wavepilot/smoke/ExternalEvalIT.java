package org.example.wavepilot.smoke;

import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationRun;
import org.example.wavepilot.evaluation.EvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-model evaluation smoke, run only through the explicit `external-eval` Maven profile:
 *   mvn -B -Pexternal-eval -DDASHSCOPE_API_KEY=... verify
 *
 * Spec cases run through the production DashScope-backed parser; cases the external model
 * cannot cover fail with an explicit NOT_COVERED reason. Without a real key this test fails
 * loudly, and a passing run is the only legitimate claim that real-model eval executed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("external-eval")
class ExternalEvalIT {

    @Autowired private EvaluationService evaluationService;

    @Test
    void externalModelExecutesSpecCasesThroughTheRealParser() {
        EvaluationRun run = evaluationService.run("default", "external");
        assertEquals("external", run.modelName());

        // Spec cases must carry real outcomes, never execution errors.
        for (String caseId : java.util.List.of("C-001", "C-002", "C-003", "C-005")) {
            EvaluationCaseResult result = caseResult(run, caseId);
            assertFalse("EXECUTION_ERROR".equals(result.actualResult()),
                    caseId + " must run through the real parser, got: " + result.failureReason());
            assertTrue(result.actualResult() != null && !result.actualResult().isBlank(),
                    caseId + " must record a real outcome");
        }
        // Uncovered platform/tool cases must be recorded as NOT_COVERED, not silently passed.
        EvaluationCaseResult toolCase = caseResult(run, "C-009");
        assertFalse(toolCase.passed(), "the external model does not cover tool cases");
        assertTrue(toolCase.failureReason().contains("does not cover"),
                "uncovered cases must carry an explicit NOT_COVERED reason");
        // The run preserves per-case evidence and computed metrics.
        assertEquals(24, run.results().size());
        assertFalse(run.metrics().isEmpty());
    }

    private EvaluationCaseResult caseResult(EvaluationRun run, String caseId) {
        return run.results().stream().filter(result -> result.caseId().equals(caseId))
                .findFirst().orElseThrow();
    }
}
