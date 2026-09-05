package org.example.wavepilot.experiment.repository;

import org.example.wavepilot.experiment.model.ExperimentJob;

import java.util.List;
import java.util.Optional;

public interface ExperimentJobRepository {
    ExperimentJob save(ExperimentJob job);
    Optional<ExperimentJob> findById(String jobId);
    List<ExperimentJob> findAll();

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
        synchronized (this) {
            if (job.getStatus() != org.example.wavepilot.experiment.model.ExperimentStatus.QUEUED) return false;
            job.changeStatus(org.example.wavepilot.experiment.model.ExperimentStatus.RUNNING, "Worker claimed job");
            save(job);
            return true;
        }
    }
}
