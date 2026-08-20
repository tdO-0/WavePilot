package org.example.wavepilot.evaluation;

import org.springframework.stereotype.Component;

import java.util.List;

/** Reference offline model: scripted to behave correctly on every dataset case. */
@Component
public class ReferenceStubModel implements EvaluationModel {

    public static final String DEFAULT_NAME = "stub-v1";

    @Override
    public String name() { return DEFAULT_NAME; }

    @Override
    public EvaluationModelResponse run(EvaluationCase evaluationCase) {
        return switch (evaluationCase.caseType()) {
            case COMPLETE_SPEC, INVALID_PARAMETER -> new EvaluationModelResponse(
                    StubModelParser.parseSpec(evaluationCase.input()), List.of(), null);
            case MISSING_PARAMETER -> new EvaluationModelResponse(
                    StubModelParser.parseSpec(evaluationCase.input()),
                    StubModelParser.missingParameters(evaluationCase.input()), null);
            case TOOL_SELECTION, TOOL_SECURITY -> new EvaluationModelResponse(
                    null, List.of(), StubModelParser.pickTool(evaluationCase.input()));
            default -> new EvaluationModelResponse(null, List.of(), null);
        };
    }
}
