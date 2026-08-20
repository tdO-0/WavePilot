package org.example.wavepilot.evaluation;

import org.example.wavepilot.experiment.model.ExperimentSpec;

import java.util.List;

/**
 * The evaluation-time model boundary. Default evaluation uses scripted stub models that never
 * call DashScope; the external-eval profile may register a real model-backed implementation.
 * The stub response is a deterministic function of the case input so results are reproducible.
 */
public interface EvaluationModel {

    String name();

    EvaluationModelResponse run(EvaluationCase evaluationCase);

    record EvaluationModelResponse(
            ExperimentSpec parsedSpec,
            List<String> missingParameters,
            String selectedTool) {

        public EvaluationModelResponse {
            missingParameters = missingParameters == null ? List.of() : List.copyOf(missingParameters);
        }
    }
}
