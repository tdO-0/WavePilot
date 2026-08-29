package org.example.wavepilot.scientific.service;

import org.example.wavepilot.modelrouting.ModelRouter;
import org.example.wavepilot.modelrouting.ModelTaskType;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.example.wavepilot.scientific.model.ScientificExperimentPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScientificPlanner {
    private final ModelRouter modelRouter;
    private final ScientificPlanModel planModel;
    private final ScientificPlanSchemaValidator schemaValidator;
    private final boolean modelMode;

    public ScientificPlanner(ModelRouter modelRouter) {
        this(modelRouter, List.of(), new ScientificPlanSchemaValidator(), "deterministic");
    }

    @Autowired
    public ScientificPlanner(ModelRouter modelRouter, List<ScientificPlanModel> models,
                             ScientificPlanSchemaValidator schemaValidator,
                             @Value("${wavepilot.scientific.planner-mode:deterministic}") String mode) {
        this.modelRouter = modelRouter;
        this.planModel = models.isEmpty() ? null : models.get(0);
        this.schemaValidator = schemaValidator;
        this.modelMode = "model".equalsIgnoreCase(mode);
    }

    public ScientificExperimentPlan plan(AgentRun run, int iteration) {
        boolean requestModel = modelMode && planModel != null
                && run.getTrace().getModelCalls() < run.getGoal().budget().maxModelCalls()
                && remainingTokenBudget(run) > 0;
        var routing = modelRouter.route(ModelTaskType.PLANNING_REPLANNING, requestModel);
        run.getTrace().recordRouting(routing);
        if (requestModel && routing.modelCall()) {
            try {
                ScientificPlanProposal proposal = planModel.propose(run, iteration,
                        ScientificPlanSchemaValidator.REGISTERED);
                return schemaValidator.validateAndBuild(proposal, run, iteration);
            } catch (RuntimeException invalidModelPlan) {
                run.getTrace().recordInvalidPlanProposal();
                run.getTrace().recordInvalidToolCall();
            }
        }
        return schemaValidator.validateAndBuild(new ScientificPlanProposal(List.of(
                ScientificCapability.RETRIEVE_EVIDENCE.name(),
                ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT.name(),
                ScientificCapability.VERIFY_GROUNDED_RESULT.name())), run, iteration);
    }

    private long remainingTokenBudget(AgentRun run) {
        long used = (run.getTrace().getInputTokens() == null ? 0 : run.getTrace().getInputTokens())
                + (run.getTrace().getOutputTokens() == null ? 0 : run.getTrace().getOutputTokens());
        return Math.max(0, run.getGoal().budget().maxTokens() - used);
    }
}
