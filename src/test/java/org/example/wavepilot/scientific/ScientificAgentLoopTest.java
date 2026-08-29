package org.example.wavepilot.scientific;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.AgentRunState;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.GoalOperator;
import org.example.wavepilot.scientific.model.ParameterBounds;
import org.example.wavepilot.scientific.model.ReplanDecision;
import org.example.wavepilot.scientific.model.RunBudget;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.example.wavepilot.scientific.repository.AgentRunRepository;
import org.example.wavepilot.scientific.service.BoundedScientificReplanner;
import org.example.wavepilot.scientific.service.ScientificAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "wavepilot.knowledge.repository=memory",
        "wavepilot.embedding.offline=true",
        "wavepilot.runner.type=mock",
        "wavepilot.artifacts.root=target/scientific-agent-artifacts",
        "wavepilot.scientific.run-store=target/scientific-agent-runs"
})
class ScientificAgentLoopTest {
    @Autowired ScientificAgentService agentService;
    @Autowired ExperimentService experimentService;
    @Autowired AgentRunRepository runRepository;
    @Autowired BoundedScientificReplanner replanner;

    @Test
    void mockRunnerCompletesPlanExecuteVerifyReplanLoopOffline() {
        AgentRun run = agentService.start(goal("GOAL-LOOP", spec(.20, .30), .82,
                new RunBudget(4, 4, 0, 0, 1, Duration.ofMinutes(1))));

        assertEquals(AgentRunState.SUCCEEDED, run.getState());
        assertEquals(3, run.getExperimentCount());
        assertEquals(2, run.getReplanCount());
        assertEquals(3, run.getObservations().size());
        assertEquals(3, run.getVerificationResults().size());
        assertTrue(run.getVerificationResults().get(2).goalSatisfied());
        assertTrue(run.getVerificationResults().stream().allMatch(value -> value.artifactsComplete()
                && value.grounded()));
        assertEquals(0, run.getTrace().getModelCalls());
        assertTrue(run.getCurrentPlan().steps().stream().allMatch(step ->
                step.capability() == ScientificCapability.RETRIEVE_EVIDENCE
                        || step.capability() == ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT
                        || step.capability() == ScientificCapability.VERIFY_GROUNDED_RESULT));
        System.out.printf("SCIENTIFIC_AGENT_RUN status=%s iterations=%d experiments=%d replans=%d metric=%.6f%n",
                run.getState(), run.getIterationCount(), run.getExperimentCount(), run.getReplanCount(),
                run.getVerificationResults().get(2).metricValue());
    }

    @Test
    void plannerCannotBypassJavaValidator() {
        ExperimentSpec invalid = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(48), 0, .1, .05, 10, 10, 1,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "invalid planner proposal");
        AgentRun run = agentService.start(new ExperimentGoal("GOAL-INVALID", "invalid spec must fail",
                invalid, "averageAccuracy", GoalOperator.GREATER_THAN_OR_EQUAL, .5,
                Map.of(), RunBudget.offlineDefaults()));

        assertEquals(AgentRunState.FAILED, run.getState());
        assertEquals(0, run.getExperimentCount());
        assertTrue(run.getTerminalReason().contains("Java Validator"));
    }

    @Test
    void impossibleGoalAlwaysTerminatesAtBudget() {
        AgentRun run = agentService.start(goal("GOAL-BUDGET", spec(0, .1), 1.0,
                new RunBudget(1, 1, 0, 0, 0, Duration.ofMinutes(1))));

        assertEquals(AgentRunState.BUDGET_EXHAUSTED, run.getState());
        assertEquals(1, run.getIterationCount());
        assertEquals(1, run.getExperimentCount());
        assertTrue(run.getState().isTerminal());
    }

    @Test
    void duplicateExecutionKeyDoesNotCreateDuplicateRunnerSideEffects() throws Exception {
        int before = experimentService.list().size();
        ExperimentJob first = experimentService.create(spec(0, .1), "TEST-IDEMPOTENCY-001");
        ExperimentJob duplicate = experimentService.create(spec(0, .1), "TEST-IDEMPOTENCY-001");

        assertEquals(first.getJobId(), duplicate.getJobId());
        assertEquals(before + 1, experimentService.list().size());
        await(first.getJobId());
    }

    @Test
    void resumeFromPostObservationCheckpointReusesExecutionAndArtifacts() {
        AgentRun completed = agentService.start(goal("GOAL-RECOVERY", spec(0, .1), .70,
                new RunBudget(2, 2, 0, 0, 1, Duration.ofMinutes(1))));
        assertEquals(AgentRunState.SUCCEEDED, completed.getState());
        int jobsBeforeResume = experimentService.list().size();
        String originalJob = completed.getExecutions().get(0).jobId();

        completed.setState(AgentRunState.VERIFYING);
        completed.setTerminalReason(null);
        completed.setVerificationResults(List.of());
        runRepository.save(completed);

        AgentRun recovered = agentService.resume(completed.getRunId());

        assertEquals(AgentRunState.SUCCEEDED, recovered.getState());
        assertEquals(jobsBeforeResume, experimentService.list().size());
        assertEquals(originalJob, recovered.getExecutions().get(0).jobId());
        assertEquals(1, recovered.getObservations().size());
        assertEquals(1, recovered.getVerificationResults().size());
        assertTrue(runRepository.findById(recovered.getRunId()).isPresent());
        System.out.printf("DURABLE_RECOVERY status=%s jobReused=%s executions=%d observations=%d%n",
                recovered.getState(), originalJob, recovered.getExecutions().size(),
                recovered.getObservations().size());
    }

    @Test
    void replannerNeverCrossesDeclaredParameterBoundary() {
        ExperimentSpec current = spec(.20, .30);
        ExperimentGoal goal = new ExperimentGoal("GOAL-BOUNDS", "bounded replan", current,
                "averageAccuracy", GoalOperator.GREATER_THAN_OR_EQUAL, .9,
                Map.of("errorRateStart", new ParameterBounds(.20, .40, .1),
                        "errorRateEnd", new ParameterBounds(.25, .40, .1)),
                new RunBudget(3, 3, 0, 0, 0, Duration.ofMinutes(1)));
        AgentRun run = new AgentRun(goal);

        ReplanDecision decision = replanner.replan(goal, current, 1, run);

        assertTrue(decision.replan());
        assertEquals(.20, decision.nextSpec().errorRateStart(), 1.0e-12);
        assertEquals(.25, decision.nextSpec().errorRateEnd(), 1.0e-12);
        assertTrue(replanner.withinBounds(decision.nextSpec(), goal.parameterBounds()));
        assertTrue(replanner.withinBounds(current, goal.parameterBounds()));
    }

    private ExperimentGoal goal(String id, ExperimentSpec spec, double target, RunBudget budget) {
        return new ExperimentGoal(id, "search a bounded error-rate range that satisfies an accuracy threshold",
                spec, "averageAccuracy", GoalOperator.GREATER_THAN_OR_EQUAL, target,
                Map.of("errorRateStart", new ParameterBounds(0, .4, .1),
                        "errorRateEnd", new ParameterBounds(.05, .5, .1)), budget);
    }

    private ExperimentSpec spec(double start, double end) {
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), start, end, .05, 20, 10, 20,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "scientific agent offline loop");
    }

    private void await(String jobId) throws Exception {
        for (int i = 0; i < 500; i++) {
            ExperimentStatus status = experimentService.progress(jobId).status();
            if (status.isTerminal()) {
                assertEquals(ExperimentStatus.SUCCEEDED, status);
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job did not terminate: " + jobId);
    }
}
