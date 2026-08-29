package org.example.wavepilot.scientific.model;

import org.example.wavepilot.experiment.model.ExperimentSpec;

public record ExperimentPlanStep(
        String stepId,
        ScientificCapability capability,
        ExperimentSpec experimentSpec,
        PlanStepStatus status) {
    public ExperimentPlanStep {
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId is required");
        if (capability == null) throw new IllegalArgumentException("capability is required");
        status = status == null ? PlanStepStatus.PENDING : status;
        if (capability == ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT && experimentSpec == null) {
            throw new IllegalArgumentException("execution step requires an ExperimentSpec");
        }
    }

    public ExperimentPlanStep withStatus(PlanStepStatus next) {
        return new ExperimentPlanStep(stepId, capability, experimentSpec, next);
    }
}
