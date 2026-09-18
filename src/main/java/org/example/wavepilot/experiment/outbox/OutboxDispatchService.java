package org.example.wavepilot.experiment.outbox;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The single transactional boundary between the job state machine and the outbox journal.
 * Methods here are proxied by Spring, so callers in other beans get real transactions;
 * a failure of either write rolls the other back.
 */
@Service
@ConditionalOnProperty(name = "wavepilot.job-repository", havingValue = "mysql")
public class OutboxDispatchService {

    private final ExperimentJobRepository jobRepository;
    private final OutboxEventStore eventStore;

    public OutboxDispatchService(ExperimentJobRepository jobRepository, OutboxEventStore eventStore) {
        this.jobRepository = jobRepository;
        this.eventStore = eventStore;
    }

    /**
     * Persist the (already applied in memory) transition into {@code expectedStatus} and
     * journal the dispatch event atomically. The API request returns 202 once this commits;
     * publishing is the relay's job afterwards.
     */
    @Transactional
    public void enqueueQueued(ExperimentJob job, ExperimentStatus expectedStatus) {
        jobRepository.save(job, expectedStatus);
        eventStore.append(job.getJobId());
    }

    /** Re-POST with the same Idempotency-Key: re-journal without touching job state. */
    @Transactional
    public void ensureQueuedEvent(ExperimentJob job) {
        eventStore.append(job.getJobId());
    }

    /**
     * Lease recovery: conditionally move the expired job back to QUEUED and journal a new
     * dispatch event in the same transaction. Returns false when the conditional update
     * lost the race (somebody else reclaimed the job first).
     */
    @Transactional
    public boolean requeueQueued(ExperimentJob job, ExperimentStatus expectedStatus) {
        if (!jobRepository.requeue(job, expectedStatus, Instant.now())) return false;
        eventStore.append(job.getJobId());
        return true;
    }
}
