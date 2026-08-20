package org.example.wavepilot.evaluation;

import java.util.List;

/**
 * One fixed offline evaluation case. The dataset ships with expected outcome, expected tool,
 * forbidden tools, expected status and expected fields; every metric is computed from the
 * actual execution results of these cases, never from hardcoded percentages.
 */
public record EvaluationCase(
        String caseId,
        EvaluationCaseType caseType,
        String description,
        String input,
        String expectedResult,
        String expectedTool,
        List<String> forbiddenTools,
        String expectedStatus,
        List<String> expectedFields,
        List<String> tags) {

    public EvaluationCase {
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        expectedFields = expectedFields == null ? List.of() : List.copyOf(expectedFields);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
