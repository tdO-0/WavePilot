package org.example.wavepilot.scientific.model;

import org.example.wavepilot.experiment.model.ExperimentSpec;

import java.util.Map;
import java.util.UUID;

public record ExperimentGoal(
        String goalId,
        String description,
        ExperimentSpec initialSpec,
        String metricName,
        GoalOperator operator,
        double targetValue,
        Map<String, ParameterBounds> parameterBounds,
        RunBudget budget) {
    public ExperimentGoal {
        goalId = goalId == null || goalId.isBlank()
                ? "GOAL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase() : goalId;
        if (description == null || description.isBlank()) throw new IllegalArgumentException("goal description is required");
        if (initialSpec == null) throw new IllegalArgumentException("initial ExperimentSpec is required");
        if (metricName == null || metricName.isBlank()) throw new IllegalArgumentException("metricName is required");
        if (operator == null) throw new IllegalArgumentException("goal operator is required");
        if (!Double.isFinite(targetValue)) throw new IllegalArgumentException("targetValue must be finite");
        parameterBounds = parameterBounds == null ? Map.of() : Map.copyOf(parameterBounds);
        budget = budget == null ? RunBudget.offlineDefaults() : budget;
    }
}
