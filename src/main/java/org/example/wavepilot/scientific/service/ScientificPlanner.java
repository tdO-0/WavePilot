package org.example.wavepilot.scientific.service;

import org.example.wavepilot.modelrouting.ModelRouter;
import org.example.wavepilot.modelrouting.ModelTaskType;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ExperimentPlanStep;
import org.example.wavepilot.scientific.model.PlanStepStatus;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.example.wavepilot.scientific.model.ScientificExperimentPlan;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ScientificPlanner {
    private final ModelRouter modelRouter;

    public ScientificPlanner(ModelRouter modelRouter) { this.modelRouter = modelRouter; }

    public ScientificExperimentPlan plan(AgentRun run, int iteration) {
        run.getTrace().recordRouting(modelRouter.route(ModelTaskType.PLANNING_REPLANNING, false));
        String prefix = run.getRunId() + "-I" + iteration;
        return new ScientificExperimentPlan("SPLAN-" + prefix, iteration, List.of(
                new ExperimentPlanStep(prefix + "-RETRIEVE", ScientificCapability.RETRIEVE_EVIDENCE,
                        null, PlanStepStatus.PENDING),
                new ExperimentPlanStep(prefix + "-EXECUTE", ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT,
                        run.getCurrentSpec(), PlanStepStatus.PENDING),
                new ExperimentPlanStep(prefix + "-VERIFY", ScientificCapability.VERIFY_GROUNDED_RESULT,
                        null, PlanStepStatus.PENDING)
        ), Instant.now());
    }
}
