package org.example.wavepilot.scientific.service;

import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ExperimentPlanStep;
import org.example.wavepilot.scientific.model.PlanStepStatus;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.example.wavepilot.scientific.model.ScientificExperimentPlan;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Java schema and capability allow-list for model-generated plans. */
@Component
public class ScientificPlanSchemaValidator {
    public static final Set<ScientificCapability> REGISTERED = Set.copyOf(EnumSet.of(
            ScientificCapability.RETRIEVE_EVIDENCE,
            ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT,
            ScientificCapability.VERIFY_GROUNDED_RESULT,
            ScientificCapability.ANALYZE_RESULT,
            ScientificCapability.REPLAN_EXPERIMENT));

    public ScientificExperimentPlan validateAndBuild(ScientificPlanProposal proposal,
                                                     AgentRun run, int iteration) {
        if (proposal == null || proposal.capabilities().size() < 3
                || proposal.capabilities().size() > REGISTERED.size()) {
            throw new IllegalArgumentException("plan must contain 3 to " + REGISTERED.size() + " capabilities");
        }
        List<ScientificCapability> capabilities = new ArrayList<>();
        for (String raw : proposal.capabilities()) {
            try {
                ScientificCapability capability = ScientificCapability.valueOf(raw.toUpperCase(Locale.ROOT));
                if (!REGISTERED.contains(capability)) throw new IllegalArgumentException("unregistered capability: " + raw);
                capabilities.add(capability);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown or unregistered capability: " + raw, e);
            }
        }
        if (new HashSet<>(capabilities).size() != capabilities.size()) {
            throw new IllegalArgumentException("plan capabilities must not be duplicated");
        }
        require(capabilities, ScientificCapability.RETRIEVE_EVIDENCE);
        require(capabilities, ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT);
        require(capabilities, ScientificCapability.VERIFY_GROUNDED_RESULT);
        int previousRank = -1;
        for (ScientificCapability capability : capabilities) {
            int currentRank = rank(capability);
            if (currentRank <= previousRank) {
                throw new IllegalArgumentException(
                        "plan must follow RETRIEVE -> EXECUTE -> ANALYZE? -> VERIFY -> REPLAN?");
            }
            previousRank = currentRank;
        }
        String prefix = run.getRunId() + "-I" + iteration;
        List<ExperimentPlanStep> steps = capabilities.stream().map(capability ->
                new ExperimentPlanStep(prefix + "-" + suffix(capability), capability,
                        capability == ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT
                                ? run.getCurrentSpec() : null, PlanStepStatus.PENDING)).toList();
        return new ScientificExperimentPlan("SPLAN-" + prefix, iteration, steps, Instant.now());
    }

    private void require(List<ScientificCapability> capabilities, ScientificCapability required) {
        if (!capabilities.contains(required)) throw new IllegalArgumentException("missing required capability: " + required);
    }

    private int rank(ScientificCapability capability) {
        return switch (capability) {
            case RETRIEVE_EVIDENCE -> 0;
            case EXECUTE_VALIDATED_EXPERIMENT -> 1;
            case ANALYZE_RESULT -> 2;
            case VERIFY_GROUNDED_RESULT -> 3;
            case REPLAN_EXPERIMENT, REPLAN_BOUNDED_PARAMETERS -> 4;
        };
    }

    private String suffix(ScientificCapability capability) {
        return switch (capability) {
            case RETRIEVE_EVIDENCE -> "RETRIEVE";
            case EXECUTE_VALIDATED_EXPERIMENT -> "EXECUTE";
            case VERIFY_GROUNDED_RESULT -> "VERIFY";
            case ANALYZE_RESULT -> "ANALYZE";
            case REPLAN_EXPERIMENT, REPLAN_BOUNDED_PARAMETERS -> "REPLAN";
        };
    }
}
