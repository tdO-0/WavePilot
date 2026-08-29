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
import org.example.wavepilot.scientific.model.VerificationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Component
public class BoundedScientificReplanner {
    private final ExperimentSpecValidator validator;
    private final ModelRouter modelRouter;
    private final SemanticReplanModel semanticModel;
    private final boolean modelMode;

    public BoundedScientificReplanner(ExperimentSpecValidator validator, ModelRouter modelRouter) {
        this(validator, modelRouter, List.of(), "deterministic");
    }

    @Autowired
    public BoundedScientificReplanner(ExperimentSpecValidator validator, ModelRouter modelRouter,
                                      List<SemanticReplanModel> models,
                                      @Value("${wavepilot.scientific.replanner-mode:deterministic}") String mode) {
        this.validator = validator;
        this.modelRouter = modelRouter;
        this.semanticModel = models.isEmpty() ? null : models.get(0);
        this.modelMode = "model".equalsIgnoreCase(mode);
    }

    public ReplanDecision replan(ExperimentGoal goal, ExperimentSpec current, int afterIteration,
                                 org.example.wavepilot.scientific.model.AgentRun run) {
        boolean requestModel = modelMode && semanticModel != null
                && run.getTrace().getModelCalls() < run.getGoal().budget().maxModelCalls()
                && remainingTokenBudget(run) > 0;
        var routing = modelRouter.route(ModelTaskType.PLANNING_REPLANNING, requestModel);
        run.getTrace().recordRouting(routing);
        if (goal.parameterBounds().isEmpty()) {
            return new ReplanDecision(afterIteration, false, null,
                    "No bounded parameter is available for replan", true, Instant.now());
        }
        if (requestModel && routing.modelCall()) {
            try {
                VerificationResult verification = run.getVerificationResults().isEmpty() ? null
                        : run.getVerificationResults().get(run.getVerificationResults().size() - 1);
                ExperimentSpec proposed = semanticModel.propose(new SemanticReplanContext(goal, current,
                        run.latestObservation(), verification, run.getRetrievedEvidence(),
                        run.getReplanDecisions()));
                String rejection = proposalRejection(current, proposed, goal.parameterBounds());
                if (rejection == null) {
                    return new ReplanDecision(afterIteration, true, proposed,
                            "Model semantic proposal accepted by ParameterBounds and Java Validator",
                            false, Instant.now());
                }
                run.getTrace().recordInvalidExperimentSpec();
            } catch (RuntimeException invalidModelProposal) {
                run.getTrace().recordInvalidExperimentSpec();
            }
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
                case "randomSeed" -> spec.randomSeed();
                default -> spec.customParameters().get(entry.getKey());
            };
            if (!(value instanceof Number number) || !entry.getValue().contains(number.doubleValue())) return false;
        }
        return true;
    }

    public boolean withinStepLimits(ExperimentSpec current, ExperimentSpec proposed,
                                    Map<String, ParameterBounds> bounds) {
        for (Map.Entry<String, ParameterBounds> entry : bounds.entrySet()) {
            Number before = numericValue(current, entry.getKey());
            Number after = numericValue(proposed, entry.getKey());
            if (before == null || after == null
                    || Math.abs(after.doubleValue() - before.doubleValue())
                    > entry.getValue().maximumChangePerReplan() + 1.0e-12) return false;
        }
        return true;
    }

    private String proposalRejection(ExperimentSpec current, ExperimentSpec proposed,
                                     Map<String, ParameterBounds> bounds) {
        if (proposed == null) return "model returned no ExperimentSpec";
        if (!changesOnlyBoundedParameters(current, proposed, bounds)) {
            return "proposal changed an unregistered parameter or structural field";
        }
        if (!withinBounds(proposed, bounds)) return "proposal exceeded absolute ParameterBounds";
        if (!withinStepLimits(current, proposed, bounds)) return "proposal exceeded per-replan change limit";
        ValidationResult validation = validator.validate(proposed);
        if (!validation.valid()) return "Java Validator rejected proposal: " + String.join("; ", validation.errors());
        if (proposed.equals(current)) return "proposal did not change the ExperimentSpec";
        return null;
    }

    /** Model proposals may change only numeric parameters explicitly registered in ParameterBounds. */
    public boolean changesOnlyBoundedParameters(ExperimentSpec current, ExperimentSpec proposed,
                                                Map<String, ParameterBounds> bounds) {
        if (current == null || proposed == null) return false;
        if (current.experimentType() != proposed.experimentType()
                || !Objects.equals(current.codeLengths(), proposed.codeLengths())
                || !Objects.equals(current.outputTypes(), proposed.outputTypes())
                || !Objects.equals(current.description(), proposed.description())
                || !Objects.equals(current.experimentTypeId(), proposed.experimentTypeId())) return false;
        if (!unchangedUnlessBounded("errorRateStart", current.errorRateStart(), proposed.errorRateStart(), bounds)
                || !unchangedUnlessBounded("errorRateEnd", current.errorRateEnd(), proposed.errorRateEnd(), bounds)
                || !unchangedUnlessBounded("errorRateStep", current.errorRateStep(), proposed.errorRateStep(), bounds)
                || !unchangedUnlessBounded("sampleCount", current.sampleCount(), proposed.sampleCount(), bounds)
                || !unchangedUnlessBounded("monteCarloTimes", current.monteCarloTimes(), proposed.monteCarloTimes(), bounds)
                || !unchangedUnlessBounded("randomSeed", current.randomSeed(), proposed.randomSeed(), bounds)) return false;
        Set<String> customNames = new HashSet<>(current.customParameters().keySet());
        customNames.addAll(proposed.customParameters().keySet());
        return customNames.stream().allMatch(name -> bounds.containsKey(name)
                || Objects.equals(current.customParameters().get(name), proposed.customParameters().get(name)));
    }

    private boolean unchangedUnlessBounded(String name, double before, double after,
                                           Map<String, ParameterBounds> bounds) {
        return bounds.containsKey(name) || Double.compare(before, after) == 0;
    }

    private Number numericValue(ExperimentSpec spec, String name) {
        Object value = switch (name) {
            case "errorRateStart" -> spec.errorRateStart();
            case "errorRateEnd" -> spec.errorRateEnd();
            case "errorRateStep" -> spec.errorRateStep();
            case "sampleCount" -> spec.sampleCount();
            case "monteCarloTimes" -> spec.monteCarloTimes();
            case "randomSeed" -> spec.randomSeed();
            default -> spec.customParameters().get(name);
        };
        return value instanceof Number number ? number : null;
    }

    private long remainingTokenBudget(org.example.wavepilot.scientific.model.AgentRun run) {
        long used = (run.getTrace().getInputTokens() == null ? 0 : run.getTrace().getInputTokens())
                + (run.getTrace().getOutputTokens() == null ? 0 : run.getTrace().getOutputTokens());
        return Math.max(0, run.getGoal().budget().maxTokens() - used);
    }
}
