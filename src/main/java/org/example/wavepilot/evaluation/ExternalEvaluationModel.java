package org.example.wavepilot.evaluation;

import org.example.wavepilot.agent.spec.ExperimentSpecParseResult;
import org.example.wavepilot.agent.spec.ExperimentSpecParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real-model evaluation variant, activated only by the explicit external-eval profile
 * (wavepilot.evaluation.external-model=true). Spec cases run through the production
 * DashScope-backed ExperimentSpecParser; tool and platform cases it cannot cover fail with
 * an explicit NOT_COVERED reason so nobody can claim a full external eval that never ran.
 */
@Component
@ConditionalOnProperty(name = "wavepilot.evaluation.external-model", havingValue = "true")
public class ExternalEvaluationModel implements EvaluationModel {

    public static final String NAME = "external";

    private final ExperimentSpecParser parser;

    public ExternalEvaluationModel(ExperimentSpecParser parser) {
        this.parser = parser;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public EvaluationModelResponse run(EvaluationCase evaluationCase) {
        return switch (evaluationCase.caseType()) {
            case COMPLETE_SPEC, INVALID_PARAMETER -> {
                ExperimentSpecParseResult result = parser.parse(evaluationCase.input());
                yield new EvaluationModelResponse(result.experimentSpec(), List.of(), null);
            }
            case MISSING_PARAMETER -> {
                ExperimentSpecParseResult result = parser.parse(evaluationCase.input());
                yield new EvaluationModelResponse(result.experimentSpec(), result.missingFields(), null);
            }
            default -> throw new UnsupportedOperationException(
                    "external model does not cover case type " + evaluationCase.caseType()
                            + " (spec cases only)");
        };
    }
}
