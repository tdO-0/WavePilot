package org.example.wavepilot.experiment.lease;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.metrics.JobExecutionMetrics;
import org.example.wavepilot.experiment.outbox.OutboxDispatchService;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Expired-lease recovery. Runs on the api and worker roles (any node with the MySQL
 * backend); the conditional updates make concurrent scanners safe.
 *
 * Classification is deliberately conservative — re-running an external experiment whose
 * side effects may still exist is worse than asking a human:
 * <ul>
 *   <li>RUNNING/VALIDATING_RESULT with no validated artifacts and a side-effect-free runner
 *       (default: mock) → re-queued atomically with a fresh outbox event;</li>
 *   <li>any other runner (e.g. local-matlab) → UNCERTAIN, never re-run automatically;</li>
 *   <li>VALIDATING_RESULT with complete, SHA-256-verified, validated artifacts → rebuilt
 *       to SUCCEEDED from the evidence already on disk (the crash window between
 *       markArtifactsValidated and the SUCCEEDED write);</li>
 *   <li>SUCCEEDED/FAILED/CANCELLED are never selected by the scan at all.</li>
 * </ul>
 * The fencing_token + version predicates reject stale observations, so a zombie owner that
 * wakes up mid-recovery can never overwrite the recovered state.
 */
@Component
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("('${wavepilot.node-role:standalone}' == 'api' or '${wavepilot.node-role:standalone}' == 'worker')"
        + " and '${wavepilot.job-repository:in-memory}' == 'mysql'")
public class LeaseRecoveryScanner {
    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryScanner.class);

    private final ExperimentJobRepository repository;
    private final ExperimentStateMachine stateMachine;
    private final ArtifactRegistry artifactRegistry;
    private final OutboxDispatchService outboxDispatch;
    private final LeaseProperties properties;
    private final LeaseMetrics metrics;
    private final JobExecutionMetrics jobMetrics;
    private final String runnerType;

    public LeaseRecoveryScanner(ExperimentJobRepository repository, ExperimentStateMachine stateMachine,
                                ArtifactRegistry artifactRegistry, OutboxDispatchService outboxDispatch,
                                LeaseProperties properties, LeaseMetrics metrics,
                                JobExecutionMetrics jobMetrics,
                                @Value("${wavepilot.runner.type:mock}") String runnerType) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.artifactRegistry = artifactRegistry;
        this.outboxDispatch = outboxDispatch;
        this.properties = properties;
        this.metrics = metrics;
        this.jobMetrics = jobMetrics;
        this.runnerType = runnerType;
    }

    @Scheduled(fixedDelayString = "${wavepilot.lease.scan-interval-ms:10000}")
    public void scan() {
        if (!properties.enabled()) return;
        Instant now = Instant.now();
        // Small grace window absorbs clock skew between the worker and this node.
        Instant expiredBefore = now.minus(properties.scanGrace());
        List<ExperimentJob> expired = repository.findExpiredLeases(expiredBefore, properties.scanBatch());
        for (ExperimentJob job : expired) {
            try {
                recover(job);
            } catch (RuntimeException e) {
                // One undecidable job must not starve the rest; the next scan re-visits it.
                log.error("Lease recovery failed for {}", job.getJobId(), e);
            }
        }
    }

    private void recover(ExperimentJob job) {
        log.warn("Lease expired for {} (status {}, owner {}, external {})", job.getJobId(),
                job.getStatus(), job.getWorkerId(), job.getExternalJobId());
        if (job.getStatus() == ExperimentStatus.VALIDATING_RESULT && hasValidatedArtifacts(job)) {
            rebuildSucceeded(job);
        } else if (isSideEffectFreeRunner()) {
            requeue(job);
        } else {
            markUncertain(job);
        }
    }

    private boolean isSideEffectFreeRunner() {
        return properties.safeRequeueRunnerTypes().contains(runnerType);
    }

    /** Complete validated artifact set: every registered artifact verified by SHA-256. */
    private boolean hasValidatedArtifacts(ExperimentJob job) {
        List<ArtifactRecord> artifacts = artifactRegistry.listByJobId(job.getJobId());
        if (artifacts.isEmpty()) return false;
        for (ArtifactRecord artifact : artifacts) {
            if (!artifact.validated() || !artifactRegistry.verify(artifact.artifactId())) return false;
        }
        return true;
    }

    private void requeue(ExperimentJob job) {
        ExperimentStatus from = job.getStatus();
        job.changeStatus(ExperimentStatus.QUEUED, "Recovered: worker lease expired before verified side effects");
        job.setWorkerId(null);
        job.setLeaseUntil(null);
        job.setHeartbeatAt(null);
        // Conditional update + fresh dispatch event commit together; the fence bump in SQL
        // revokes the dead owner's token even if its process comes back.
        if (outboxDispatch.requeueQueued(job, from)) {
            job.setFencingToken(job.getFencingToken() + 1);
            job.setVersion(job.getVersion() + 1);
            metrics.expiredRequeued();
            jobMetrics.statusChanged(from, ExperimentStatus.QUEUED);
            log.info("Re-queued {} after expired lease ({} attempt {})", job.getJobId(), from, job.getAttempt());
        }
    }

    private void markUncertain(ExperimentJob job) {
        ExperimentStatus from = job.getStatus();
        job.setFailureReason("Worker lease expired while an external runner may still be executing; "
                + "manual verification required");
        stateMachine.transition(job, ExperimentStatus.UNCERTAIN, job.getFailureReason());
        repository.save(job, from); // fencing + version predicates reject stale observations
        metrics.expiredUncertain();
        jobMetrics.statusChanged(from, ExperimentStatus.UNCERTAIN);
    }

    private void rebuildSucceeded(ExperimentJob job) {
        job.updateProgress(100, "VALIDATING_RESULT",
                job.getPlan().totalRuns(), job.getPlan().totalRuns(),
                "Recovered from validated artifacts after worker lease expiry");
        stateMachine.transition(job, ExperimentStatus.SUCCEEDED,
                "Recovered from validated artifacts without re-execution");
        repository.save(job, ExperimentStatus.VALIDATING_RESULT);
        metrics.expiredRebuilt();
        jobMetrics.statusChanged(ExperimentStatus.VALIDATING_RESULT, ExperimentStatus.SUCCEEDED);
    }
}
