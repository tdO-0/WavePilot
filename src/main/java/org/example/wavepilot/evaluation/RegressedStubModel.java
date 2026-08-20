package org.example.wavepilot.evaluation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Regressed offline model: scripted defects on fixed case ids so baseline/candidate runs
 * differ case-by-case and regression detection is exercised without a real model.
 */
@Component
public class RegressedStubModel implements EvaluationModel {

    public static final String NAME = "stub-v2";
    private static final Set<String> DEFECTIVE_TOOL_CASES = Set.of("C-009");
    private static final Set<String> DEFECTIVE_MISSING_CASES = Set.of("C-004");

    @Override
    public String name() { return NAME; }

    @Override
    public EvaluationModelResponse run(EvaluationCase evaluationCase) {
        return switch (evaluationCase.caseType()) {
            case COMPLETE_SPEC, INVALID_PARAMETER -> new EvaluationModelResponse(
                    StubModelParser.parseSpec(evaluationCase.input()), List.of(), null);
            case MISSING_PARAMETER -> {
                List<String> missing = StubModelParser.missingParameters(evaluationCase.input());
                if (DEFECTIVE_MISSING_CASES.contains(evaluationCase.caseId()) && missing.size() > 1) {
                    yield new EvaluationModelResponse(StubModelParser.parseSpec(evaluationCase.input()),
                            List.of(missing.get(0)), null);
                }
                yield new EvaluationModelResponse(StubModelParser.parseSpec(evaluationCase.input()),
                        missing, null);
            }
            case TOOL_SELECTION, TOOL_SECURITY -> {
                String tool = StubModelParser.pickTool(evaluationCase.input());
                if (DEFECTIVE_TOOL_CASES.contains(evaluationCase.caseId())) {
                    yield new EvaluationModelResponse(null, List.of(), "getExperimentStatus");
                }
                yield new EvaluationModelResponse(null, List.of(), tool);
            }
            default -> new EvaluationModelResponse(null, List.of(), null);
        };
    }
}
