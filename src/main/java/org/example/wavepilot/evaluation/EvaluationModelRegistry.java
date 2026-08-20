package org.example.wavepilot.evaluation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the model name of an evaluation run; offline stub models are always registered. */
@Component
public class EvaluationModelRegistry {

    private final Map<String, EvaluationModel> models;

    public EvaluationModelRegistry(ReferenceStubModel reference, RegressedStubModel regressed,
                                   java.util.Optional<ExternalEvaluationModel> external) {
        Map<String, EvaluationModel> available = new LinkedHashMap<>();
        available.put(reference.name(), reference);
        available.put(regressed.name(), regressed);
        external.ifPresent(model -> available.put(model.name(), model));
        this.models = Map.copyOf(available);
    }

    public EvaluationModel require(String modelName) {
        String name = modelName == null || modelName.isBlank() ? ReferenceStubModel.DEFAULT_NAME : modelName;
        EvaluationModel model = models.get(name);
        if (model == null) {
            throw new EvaluationException("Unknown evaluation model '" + name
                    + "'; registered offline models: " + models.keySet());
        }
        return model;
    }

    public List<String> registeredModels() {
        return List.copyOf(models.keySet());
    }
}
