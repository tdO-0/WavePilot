package org.example.wavepilot.experiment.repository;

import org.example.wavepilot.experiment.model.ExperimentJob;

import java.util.List;
import java.util.Optional;

public interface ExperimentJobRepository {
    ExperimentJob save(ExperimentJob job);
    Optional<ExperimentJob> findById(String jobId);
    List<ExperimentJob> findAll();

    /**
     * Persist {@code job} but require the database row to still be in {@code expectedStatus}.
     * Non-MySQL repositories ignore the predicate; MySQL pins it into the conditional
     * UPDATE so a writer whose in-memory state drifted can never overwrite the row.
     */
    default ExperimentJob save(ExperimentJob job, org.example.wavepilot.experiment.model.ExperimentStatus expectedStatus) {
        return save(job);
    }

    default void attachSource(String jobId, String sourceJobId) {
        ExperimentJob job = findById(jobId).orElseThrow();
        job.setSourceJobId(sourceJobId);
        save(job);
    }

    default Optional<ExperimentJob> findByIdempotencyKey(String key) {
        return key == null ? Optional.empty() : findAll().stream()
                .filter(job -> key.equals(job.getIdempotencyKey())).findFirst();
    }

    /** Single-process fallback; MySQL overrides this with an INSERT and a unique index. */
    default ExperimentJob insertIfAbsent(ExperimentJob job) {
        synchronized (this) {
            return findByIdempotencyKey(job.getIdempotencyKey()).orElseGet(() -> save(job));
        }
    }

    /** Only the winner may submit to the Runner. MySQL uses an atomic conditional UPDATE. */
    default boolean tryClaim(ExperimentJob job) {
        return tryClaim(job, null);
    }

    /**
     * Claim with a worker identity. Callers set workerId/leaseUntil/heartbeatAt on the job
     * first; the MySQL implementation persists them atomically and bumps attempt and
     * fencing_token, so exactly one claimant wins and stale owners are fenced off.
     */
    default boolean tryClaim(ExperimentJob job, String workerId) {
        synchronized (this) {
            if (job.getStatus() != org.example.wavepilot.experiment.model.ExperimentStatus.QUEUED) return false;
            job.setWorkerId(workerId);
            job.changeStatus(org.example.wavepilot.experiment.model.ExperimentStatus.RUNNING, "Worker claimed job");
            save(job);
            return true;
        }
    }

    /**
     * Renew the lease without bumping the optimistic-lock version. MySQL overrides this;
     * non-MySQL repositories have no distributed lease and return 0.
     */
    default int updateHeartbeat(String jobId, String workerId, long fencingToken,
                                java.time.Instant heartbeatAt, java.time.Instant leaseUntil) {
        return 0;
    }

    /** Rows whose lease expired; used by LeaseRecoveryScanner. Non-MySQL returns empty. */
    default List<ExperimentJob> findExpiredLeases(java.time.Instant now, int limit) {
        return List.of();
    }

    /**
     * Lease recovery: move an expired job back to QUEUED with a fencing-token bump.
     * MySQL overrides this with a conditional UPDATE (status/fencing/version predicates);
     * other repositories have no distributed recovery and return false.
     */
    default boolean requeue(ExperimentJob job, org.example.wavepilot.experiment.model.ExperimentStatus expectedStatus,
                            java.time.Instant now) {
        return false;
    }
}
