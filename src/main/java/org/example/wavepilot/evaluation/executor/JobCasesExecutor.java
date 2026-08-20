package org.example.wavepilot.evaluation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.evaluation.EvaluationCase;
import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationModel;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Executes job cases against the real ExperimentService and its configured controlled runner:
 * submission, status progression and cancellation all go through the production chain.
 * The wait limit is configurable because a real MATLAB runner needs minutes while the
 * offline mock runner finishes in milliseconds.
 */
@Component
public class JobCasesExecutor implements EvaluationCaseExecutor {

    private static final long POLL_INTERVAL_MILLIS = 20;

    private final ExperimentService experimentService;
    private final ObjectMapper objectMapper;
    private final long maxWaitMillis;

    public JobCasesExecutor(ExperimentService experimentService, ObjectMapper objectMapper,
                            @Value("${wavepilot.evaluation.job-wait-timeout-millis:10000}") long maxWaitMillis) {
        this.experimentService = experimentService;
        this.objectMapper = objectMapper;
        this.maxWaitMillis = maxWaitMillis;
    }

    @Override
    public EvaluationCaseResult execute(EvaluationCase evalCase, EvaluationModel model) {
        try {
            ExperimentSpec spec = objectMapper.readValue(evalCase.input(), ExperimentSpec.class);
            ExperimentJob job = experimentService.create(spec);
            if (evalCase.caseType() == org.example.wavepilot.evaluation.EvaluationCaseType.JOB_CANCEL) {
                job = experimentService.cancel(job.getJobId());
                return jobResult(evalCase, job.getStatus().name());
            }
            ExperimentStatus status = awaitTerminal(job.getJobId());
            return jobResult(evalCase, status.name());
        } catch (Exception e) {
            return EvaluationCaseResult.failed(evalCase, "Job case execution failed: " + e.getMessage());
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

    private EvaluationCaseResult jobResult(EvaluationCase evalCase, String actualStatus) {
        boolean passed = actualStatus.equals(evalCase.expectedStatus())
                && actualStatus.startsWith(evalCase.expectedResult());
        String failureReason = passed ? null
                : "expected status " + evalCase.expectedStatus() + " but got " + actualStatus;
        if (actualStatus == null || "WAIT_TIMEOUT".equals(actualStatus)) {
            failureReason = "task did not reach a terminal state within the configured wait limit ("
                    + maxWaitMillis + " ms); a real runner may simply need longer";
        }
        return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(), evalCase.description(),
                evalCase.input(), evalCase.expectedResult(), evalCase.expectedTool(),
                evalCase.forbiddenTools(), evalCase.expectedStatus(), evalCase.expectedFields(),
                evalCase.tags(), passed, actualStatus == null ? "WAIT_TIMEOUT" : actualStatus, null,
                failureReason);
    }
}
