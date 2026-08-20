package org.example.wavepilot.evaluation;

import java.util.List;

/** Preserves the full per-case contract: input, expectation, actual outcome and failure reason. */
public record EvaluationCaseResult(
        String caseId,
        EvaluationCaseType caseType,
        String description,
        String input,
        String expectedResult,
        String expectedTool,
        List<String> forbiddenTools,
        String expectedStatus,
        List<String> expectedFields,
        List<String> tags,
        boolean passed,
        String actualResult,
        String actualTool,
        String failureReason) {

    public EvaluationCaseResult {
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        expectedFields = expectedFields == null ? List.of() : List.copyOf(expectedFields);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static EvaluationCaseResult failed(EvaluationCase evalCase, String reason) {
        return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(), evalCase.description(),
                evalCase.input(), evalCase.expectedResult(), evalCase.expectedTool(),
                evalCase.forbiddenTools(), evalCase.expectedStatus(), evalCase.expectedFields(),
                evalCase.tags(), false, "EXECUTION_ERROR", null, reason);
    }
}
