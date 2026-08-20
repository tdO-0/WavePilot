package org.example.wavepilot.evaluation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.evaluation.EvaluationCase;
import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationModel;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.replay.ReplayComparisonResult;
import org.example.wavepilot.replay.ReplayRecord;
import org.example.wavepilot.replay.ReplayService;
import org.example.wavepilot.replay.ReplayStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs a full source-job + replay chain through the production services and requires the
 * structured comparison to judge REPRODUCIBLE. The wait limit is configurable so the same
 * case works for both the millisecond mock runner and a real multi-minute MATLAB runner.
 */
@Component
public class ReplayConsistencyExecutor implements EvaluationCaseExecutor {

    private static final long POLL_INTERVAL_MILLIS = 20;

    private final ExperimentService experimentService;
    private final ReplayService replayService;
    private final ObjectMapper objectMapper;
    private final long maxWaitMillis;

    public ReplayConsistencyExecutor(ExperimentService experimentService, ReplayService replayService,
                                     ObjectMapper objectMapper,
                                     @Value("${wavepilot.evaluation.job-wait-timeout-millis:10000}") long maxWaitMillis) {
        this.experimentService = experimentService;
        this.replayService = replayService;
        this.objectMapper = objectMapper;
        this.maxWaitMillis = maxWaitMillis;
    }

    @Override
    public EvaluationCaseResult execute(EvaluationCase evalCase, EvaluationModel model) {
        try {
            ExperimentSpec spec = objectMapper.readValue(evalCase.input(), ExperimentSpec.class);
            ExperimentJob source = experimentService.create(spec);
            ExperimentStatus outcome = awaitTerminal(source.getJobId());
            if (outcome != ExperimentStatus.SUCCEEDED) {
                String reason = outcome == null
                        ? "source job did not reach a terminal state within the configured wait limit ("
                                + maxWaitMillis + " ms); a real runner may simply need longer"
                        : "source job did not succeed before replay (status " + outcome + ")";
                return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(),
                        evalCase.description(), evalCase.input(), evalCase.expectedResult(),
                        evalCase.expectedTool(), evalCase.forbiddenTools(), evalCase.expectedStatus(),
                        evalCase.expectedFields(), evalCase.tags(), false,
                        outcome == null ? "SOURCE_WAIT_TIMEOUT" : "SOURCE_FAILED: " + outcome,
                        null, reason);
            }
            ReplayRecord record = replayService.startReplay(source.getJobId(), null);
            ReplayRecord done = awaitReplay(record.getReplayId());
            if (done.getStatus() != ReplayStatus.SUCCEEDED || done.getComparison() == null) {
                return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(),
                        evalCase.description(), evalCase.input(), evalCase.expectedResult(),
                        evalCase.expectedTool(), evalCase.forbiddenTools(), evalCase.expectedStatus(),
                        evalCase.expectedFields(), evalCase.tags(), false,
                        "REPLAY_FAILED: " + done.getFailureReason(), null,
                        "replay did not complete successfully");
            }
            ReplayComparisonResult comparison = done.getComparison();
            boolean passed = ReplayComparisonResult.REPRODUCIBLE.equals(comparison.verdict());
            return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(),
                    evalCase.description(), evalCase.input(), evalCase.expectedResult(),
                    evalCase.expectedTool(), evalCase.forbiddenTools(), evalCase.expectedStatus(),
                    evalCase.expectedFields(), evalCase.tags(), passed,
                    passed ? "REPRODUCIBLE" : "NOT_REPRODUCIBLE: " + comparison.message(),
                    null, passed ? null : "replay verdict is " + comparison.verdict());
        } catch (Exception e) {
            return EvaluationCaseResult.failed(evalCase, "Replay case execution failed: " + e.getMessage());
        }
    }

    private ExperimentStatus awaitTerminal(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            ExperimentStatus status = experimentService.progress(jobId).status();
            if (status.isTerminal()) return status;
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return null;
    }

    private ReplayRecord awaitReplay(String replayId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            ReplayRecord record = replayService.get(replayId);
            if (record.getStatus() != ReplayStatus.RUNNING) return record;
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return replayService.get(replayId);
    }
}
