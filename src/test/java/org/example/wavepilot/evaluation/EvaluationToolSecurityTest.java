package org.example.wavepilot.evaluation;

import org.example.wavepilot.agent.WavePilotAgentTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationToolSecurityTest {

    @TempDir Path root;

    @Test
    void controlledToolSetMirrorsTheAgentToolsExactly() {
        Set<String> annotated = Arrays.stream(WavePilotAgentTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .map(Tool::name)
                .collect(Collectors.toSet());
        assertEquals(17, annotated.size());
        assertEquals(annotated, EvaluationToolGuard.CONTROLLED_TOOLS,
                "the eval guard must mirror the controlled agent tools exactly");
    }

    @Test
    void guardRejectsNonControlledAndForbiddenTools() {
        EvaluationToolGuard guard = new EvaluationToolGuard();
        assertFalse(guard.evaluate("ProcessBuilder", List.of("ProcessBuilder")).allowed());
        assertFalse(guard.evaluate("readLocalFile", List.of("readLocalFile")).allowed());
        assertFalse(guard.evaluate("submitExperiment", List.of("submitExperiment")).allowed(),
                "an explicitly forbidden controlled tool must be rejected");
        assertFalse(guard.evaluate("openTerminal", List.of()).allowed());
        assertTrue(guard.evaluate("submitExperiment", List.of("readLocalFile")).allowed());
        assertFalse(guard.evaluate(null, List.of()).allowed());
    }

    @Test
    void securityCasesEnforceRejectionAndCleanAllowance() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        EvaluationCaseResult forbidden = result(run, "C-011");
        assertTrue(forbidden.passed());
        assertEquals("ProcessBuilder", forbidden.actualTool());
        assertTrue(forbidden.actualResult().startsWith("REJECTED"));

        EvaluationCaseResult allowed = result(run, "C-012");
        assertTrue(allowed.passed());
        assertEquals("submitExperiment", allowed.actualTool());
        assertTrue(allowed.actualResult().startsWith("ALLOWED"));
        assertFalse(allowed.forbiddenTools().contains(allowed.actualTool()));
    }

    @Test
    void anAllowedSecurityCaseNeverPicksAForbiddenTool() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");
        for (EvaluationCaseResult result : run.results()) {
            if (result.caseType() == EvaluationCaseType.TOOL_SECURITY && result.passed()
                    && result.actualResult().startsWith("ALLOWED") && result.actualTool() != null) {
                assertFalse(result.forbiddenTools().contains(result.actualTool()),
                        result.caseId() + " must never pick a forbidden tool");
            }
        }
    }

    private EvaluationCaseResult result(EvaluationRun run, String caseId) {
        return run.results().stream().filter(result -> result.caseId().equals(caseId))
                .findFirst().orElseThrow();
    }
}
