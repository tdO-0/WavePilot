package org.example.wavepilot.scientific;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.GoalOperator;
import org.example.wavepilot.scientific.model.RunBudget;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.example.wavepilot.scientific.service.ScientificPlanProposal;
import org.example.wavepilot.scientific.service.ScientificPlanSchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScientificPlanSchemaValidatorTest {
    private final ScientificPlanSchemaValidator validator = new ScientificPlanSchemaValidator();

    @Test
    void acceptsOnlyRegisteredStructuredCapabilitiesAndAttachesValidatedSpec() {
        var plan = validator.validateAndBuild(new ScientificPlanProposal(List.of(
                "RETRIEVE_EVIDENCE", "EXECUTE_VALIDATED_EXPERIMENT", "ANALYZE_RESULT",
                "VERIFY_GROUNDED_RESULT", "REPLAN_EXPERIMENT")), run(), 1);

        assertEquals(5, plan.steps().size());
        assertEquals(ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT,
                plan.steps().get(1).capability());
        assertEquals(run().getCurrentSpec(), plan.steps().get(1).experimentSpec());
    }

    @Test
    void rejectsUnknownCapabilityMissingRequiredStepAndUnsafeOrdering() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndBuild(
                new ScientificPlanProposal(List.of("RETRIEVE_EVIDENCE", "RUN_SHELL",
                        "VERIFY_GROUNDED_RESULT")), run(), 1));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndBuild(
                new ScientificPlanProposal(List.of("RETRIEVE_EVIDENCE", "ANALYZE_RESULT",
                        "VERIFY_GROUNDED_RESULT")), run(), 1));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndBuild(
                new ScientificPlanProposal(List.of("EXECUTE_VALIDATED_EXPERIMENT",
                        "RETRIEVE_EVIDENCE", "VERIFY_GROUNDED_RESULT")), run(), 1));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndBuild(
                new ScientificPlanProposal(List.of("RETRIEVE_EVIDENCE",
                        "EXECUTE_VALIDATED_EXPERIMENT", "VERIFY_GROUNDED_RESULT",
                        "ANALYZE_RESULT")), run(), 1));
    }

    private AgentRun run() {
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0, .1, .05, 20, 10, 20,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "schema test");
        return new AgentRun(new ExperimentGoal("GOAL-SCHEMA", "schema validation", spec,
                "averageAccuracy", GoalOperator.GREATER_THAN_OR_EQUAL, .7,
                Map.of(), RunBudget.offlineDefaults()));
    }
}
