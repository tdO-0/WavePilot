package org.example.wavepilot.backend;

import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.outbox.OutboxDispatchService;
import org.example.wavepilot.experiment.outbox.OutboxEventStore;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The transactional guarantees (both writes commit, or both roll back) are exercised
 * against real MySQL in BackendOutboxIT; here we pin the write order and the failure
 * propagation that drives the rollback.
 */
class OutboxDispatchServiceTest {

    @Test
    void enqueueQueuedPersistsJobTransitionThenAppendsEvent() {
        var repository = mock(ExperimentJobRepository.class);
        var store = mock(OutboxEventStore.class);
        var dispatch = new OutboxDispatchService(repository, store);
        var job = WavePilotTestFixtures.job("JOB-TX");

        dispatch.enqueueQueued(job, ExperimentStatus.VALIDATED);

        InOrder order = inOrder(repository, store);
        order.verify(repository).save(job, ExperimentStatus.VALIDATED);
        order.verify(store).append("JOB-TX");
    }

    @Test
    void eventFailurePropagatesSoTheTransactionRollsBackTheJobSave() {
        var repository = mock(ExperimentJobRepository.class);
        var store = mock(OutboxEventStore.class);
        doThrow(new IllegalStateException("journal unavailable")).when(store).append("JOB-TX");
        var dispatch = new OutboxDispatchService(repository, store);
        var job = WavePilotTestFixtures.job("JOB-TX");

        assertThrows(IllegalStateException.class,
                () -> dispatch.enqueueQueued(job, ExperimentStatus.VALIDATED));
        verify(repository).save(job, ExperimentStatus.VALIDATED);
    }

    @Test
    void ensureQueuedEventOnlyAppendsWithoutTouchingJobState() {
        var repository = mock(ExperimentJobRepository.class);
        var store = mock(OutboxEventStore.class);
        var dispatch = new OutboxDispatchService(repository, store);
        var job = WavePilotTestFixtures.job("JOB-TX");

        dispatch.ensureQueuedEvent(job);

        verify(store).append("JOB-TX");
        verifyNoInteractions(repository);
    }

    @Test
    void requeueLosingTheRaceDoesNotAppendAnEvent() {
        var repository = mock(ExperimentJobRepository.class);
        var store = mock(OutboxEventStore.class);
        when(repository.requeue(any(), any(), any())).thenReturn(false);
        var dispatch = new OutboxDispatchService(repository, store);
        var job = WavePilotTestFixtures.job("JOB-TX");

        assertFalse(dispatch.requeueQueued(job, ExperimentStatus.RUNNING));
        verify(repository).requeue(eq(job), eq(ExperimentStatus.RUNNING), any(java.time.Instant.class));
        verifyNoInteractions(store);
    }
}
