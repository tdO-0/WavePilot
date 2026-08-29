package org.example.wavepilot.scientific.service;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.modelrouting.ModelRouter;
import org.example.wavepilot.modelrouting.ModelTaskType;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.GoalOperator;
import org.example.wavepilot.scientific.model.ParameterBounds;
import org.example.wavepilot.scientific.model.ReplanDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BoundedScientificReplanner {
    private final ExperimentSpecValidator validator;
    private final ModelRouter modelRouter;

    public BoundedScientificReplanner(ExperimentSpecValidator validator, ModelRouter modelRouter) {
        this.validator = validator;
        this.modelRouter = modelRouter;
    }

    public ReplanDecision replan(ExperimentGoal goal, ExperimentSpec current, int afterIteration,
                                 org.example.wavepilot.scientific.model.AgentRun run) {
        run.getTrace().recordRouting(modelRouter.route(ModelTaskType.PLANNING_REPLANNING, false));
        if (goal.parameterBounds().isEmpty()) {
            return new ReplanDecision(afterIteration, false, null,
                    "No bounded parameter is available for replan", true, Instant.now());
        }
        double direction = goal.operator() == GoalOperator.GREATER_THAN_OR_EQUAL ? -1.0 : 1.0;
        double start = changed("errorRateStart", current.errorRateStart(), direction, goal.parameterBounds());
        double end = changed("errorRateEnd", current.errorRateEnd(), direction, goal.parameterBounds());
        Map<String, Object> custom = new LinkedHashMap<>(current.customParameters());
        for (Map.Entry<String, ParameterBounds> entry : goal.parameterBounds().entrySet()) {
            if (entry.getKey().equals("errorRateStart") || entry.getKey().equals("errorRateEnd")) continue;
            Object value = custom.get(entry.getKey());
            if (value instanceof Number number) {
                custom.put(entry.getKey(), entry.getValue().clamp(number.doubleValue(),
                        number.doubleValue() + direction * entry.getValue().maximumChangePerReplan()));
            }
        }
        if (start >= end) {
            return new ReplanDecision(afterIteration, false, null,
                    "Bounded change cannot preserve errorRateStart < errorRateEnd", true, Instant.now());
        }
        ExperimentSpec proposed = new ExperimentSpec(current.experimentType(), current.codeLengths(),
                start, end, current.errorRateStep(), current.sampleCount(), current.monteCarloTimes(),
                current.randomSeed(), current.outputTypes(), current.description(),
                current.experimentTypeId(), custom);
        if (!withinBounds(proposed, goal.parameterBounds())) {
            return new ReplanDecision(afterIteration, false, null,
                    "Replanner proposal exceeded a declared parameter boundary", true, Instant.now());
        }
        ValidationResult validation = validator.validate(proposed);
        if (!validation.valid()) {
            return new ReplanDecision(afterIteration, false, null,
                    "Replanner proposal failed Java validation: " + String.join("; ", validation.errors()),
                    true, Instant.now());
        }
        if (proposed.equals(current)) {
            return new ReplanDecision(afterIteration, false, null,
                    "All bounded parameters reached their limits", true, Instant.now());
        }
        return new ReplanDecision(afterIteration, true, proposed,
                "Deterministic bounded step toward the goal threshold", false, Instant.now());
    }

    private double changed(String name, double current, double direction,
                           Map<String, ParameterBounds> bounds) {
        ParameterBounds bound = bounds.get(name);
        return bound == null ? current
                : bound.clamp(current, current + direction * bound.maximumChangePerReplan());
    }

    public boolean withinBounds(ExperimentSpec spec, Map<String, ParameterBounds> bounds) {
        for (Map.Entry<String, ParameterBounds> entry : bounds.entrySet()) {
            Object value = switch (entry.getKey()) {
                case "errorRateStart" -> spec.errorRateStart();
                case "errorRateEnd" -> spec.errorRateEnd();
                case "errorRateStep" -> spec.errorRateStep();
                case "sampleCount" -> spec.sampleCount();
                case "monteCarloTimes" -> spec.monteCarloTimes();
                default -> spec.customParameters().get(entry.getKey());
            };
            if (!(value instanceof Number number) || !entry.getValue().contains(number.doubleValue())) return false;
        }
        return true;
    }
}
