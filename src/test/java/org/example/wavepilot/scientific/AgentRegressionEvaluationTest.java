package org.example.wavepilot.scientific;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.evaluation.AgentRegressionComparison;
import org.example.wavepilot.evaluation.AgentRegressionEvaluationReport;
import org.example.wavepilot.evaluation.AgentRegressionEvaluationService;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.knowledge.evaluation.RetrievalEvaluationReport;
import org.example.wavepilot.knowledge.evaluation.RetrievalEvaluationService;
import org.example.wavepilot.replay.ReplayRecord;
import org.example.wavepilot.replay.ReplayRequest;
import org.example.wavepilot.replay.ReplayService;
import org.example.wavepilot.replay.ReplayStatus;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.GoalOperator;
import org.example.wavepilot.scientific.model.ParameterBounds;
import org.example.wavepilot.scientific.model.RunBudget;
import org.example.wavepilot.scientific.service.ScientificAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "wavepilot.knowledge.repository=memory",
        "wavepilot.embedding.offline=true",
        "wavepilot.runner.type=mock",
        "wavepilot.artifacts.root=target/scientific-agent-artifacts",
        "wavepilot.scientific.run-store=target/scientific-agent-runs"
})
class AgentRegressionEvaluationTest {
    @Autowired ScientificAgentService agentService;
    @Autowired RetrievalEvaluationService retrievalService;
    @Autowired ReplayService replayService;
    @Autowired AgentRegressionEvaluationService evaluationService;
    @Autowired ArtifactRegistry artifactRegistry;

    @Test
    void evaluatesNineRegressionDimensionsFromActualRunRetrievalAndReplayRecords() throws Exception {
        AgentRun run = agentService.start(goal());
        RetrievalEvaluationReport retrieval = retrievalService.run();
        ReplayRecord replay = replayService.startReplay(run.getObservations().get(0).jobId(),
                new ReplayRequest("agent regression evaluation"));
        replay = awaitReplay(replay.getReplayId());

        AgentRegressionEvaluationReport baseline = evaluationService.evaluate(run.getRunId(),
                retrieval.evaluationId(), replay.getReplayId());
        AgentRegressionEvaluationReport candidate = evaluationService.evaluate(run.getRunId(),
                retrieval.evaluationId(), replay.getReplayId());
        AgentRegressionComparison comparison = evaluationService.compare(
                baseline.evaluationId(), candidate.evaluationId());

        assertEquals(9, baseline.total());
        assertEquals(9, baseline.passed());
        assertEquals(1.0, baseline.successRate(), 1.0e-12);
        assertTrue(comparison.regressedDimensions().isEmpty());
        assertTrue(comparison.releaseAllowed());
        assertTrue(artifactRegistry.listByJobId(run.getRunId()).stream()
                .anyMatch(record -> record.type() == ArtifactType.AGENT_RUN_TRACE));
    }

    private ExperimentGoal goal() {
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0, .1, .05, 20, 10, 20,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "agent regression fixture");
        return new ExperimentGoal("GOAL-AGENT-EVAL", "polar experiment workflow accuracy threshold",
                spec, "averageAccuracy", GoalOperator.GREATER_THAN_OR_EQUAL, .70,
                Map.of("errorRateStart", new ParameterBounds(0, .4, .1),
                        "errorRateEnd", new ParameterBounds(.05, .5, .1)),
                new RunBudget(2, 2, 0, 0, 1, Duration.ofMinutes(1)));
    }

    private ReplayRecord awaitReplay(String replayId) throws Exception {
        for (int index = 0; index < 1_000; index++) {
            ReplayRecord record = replayService.get(replayId);
            if (record.getStatus() != ReplayStatus.RUNNING) {
                assertEquals(ReplayStatus.SUCCEEDED, record.getStatus());
                return record;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replay did not terminate: " + replayId);
    }
}
