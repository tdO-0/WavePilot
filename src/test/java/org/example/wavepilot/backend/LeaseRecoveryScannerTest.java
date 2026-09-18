package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.lease.LeaseMetrics;
import org.example.wavepilot.experiment.lease.LeaseProperties;
import org.example.wavepilot.experiment.lease.LeaseRecoveryScanner;
import org.example.wavepilot.experiment.metrics.JobExecutionMetrics;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.outbox.OutboxDispatchService;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LeaseRecoveryScannerTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private ExperimentJob expired(ExperimentStatus status, String externalJobId) {
        var job = WavePilotTestFixtures.job("JOB-LEASE-" + status.name());
        job.changeStatus(status, "fixture");
        job.setExternalJobId(externalJobId);
        job.setWorkerId("DEAD-WORKER");
        job.setFencingToken(1);
        job.setVersion(3);
        job.setLeaseUntil(Instant.now().minusSeconds(120));
        return job;
    }

    private LeaseRecoveryScanner scanner(ExperimentJobRepository repository,
                                         OutboxDispatchService dispatch,
                                         ArtifactRegistry registry, String runnerType) {
        return new LeaseRecoveryScanner(repository, new ExperimentStateMachine(), registry, dispatch,
                new LeaseProperties(true, java.time.Duration.ofSeconds(60), 100,
                        java.time.Duration.ofSeconds(5), List.of("mock")),
                mock(LeaseMetrics.class),
                new JobExecutionMetrics(new SimpleMeterRegistry()), runnerType);
    }

    @Test
    void expiredRunningMockJobIsSafelyRequeuedWithAFreshEvent() {
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var job = expired(ExperimentStatus.RUNNING, null);
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));
        when(dispatch.requeueQueued(any(), any())).thenReturn(true);

        scanner(repository, dispatch, new ArtifactRegistry(temp.toString(), json), "mock").scan();

        verify(dispatch).requeueQueued(job, ExperimentStatus.RUNNING);
        assertEquals(ExperimentStatus.QUEUED, job.getStatus());
        assertNull(job.getWorkerId());
        assertEquals(2, job.getFencingToken());
    }

    @Test
    void expiredRunningMatlabJobIsMarkedUncertainAndNeverRerun() {
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var job = expired(ExperimentStatus.RUNNING, "MATLAB-EXTERNAL");
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));

        scanner(repository, dispatch, new ArtifactRegistry(temp.toString(), json), "local-matlab").scan();

        verifyNoInteractions(dispatch);
        assertEquals(ExperimentStatus.UNCERTAIN, job.getStatus());
        assertTrue(job.getFailureReason().contains("manual verification"));
        verify(repository).save(job, ExperimentStatus.RUNNING);
    }

    @Test
    void submittedJobWithUnsafeRunnerIsNotRequeuedEvenWithoutExternalId() {
        // Crash window between runner.submit() and the external_job_id write: with an unsafe
        // runner we cannot prove the side effect never happened, so UNCERTAIN wins.
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var job = expired(ExperimentStatus.RUNNING, null);
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));

        scanner(repository, dispatch, new ArtifactRegistry(temp.toString(), json), "local-matlab").scan();

        verifyNoInteractions(dispatch);
        assertEquals(ExperimentStatus.UNCERTAIN, job.getStatus());
    }

    @Test
    void validatingResultWithVerifiedArtifactsRebuildsSucceededWithoutExecution() throws Exception {
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var registry = new ArtifactRegistry(temp.toString(), json);
        var job = expired(ExperimentStatus.VALIDATING_RESULT, "MOCK-EXTERNAL");
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));
        // The crash window between markArtifactsValidated and SUCCEEDED: evidence on disk.
        var artifact = registry.writeJson(job.getJobId(), ArtifactType.SUMMARY_JSON, "summary.json",
                Map.of("mock", true));
        registry.markJobValidated(job.getJobId(), "mock", true, false, "MOCK_RUNNER", "v1", "v1");
        assertTrue(registry.verify(artifact.artifactId()));

        scanner(repository, dispatch, registry, "mock").scan();

        verifyNoInteractions(dispatch);
        assertEquals(ExperimentStatus.SUCCEEDED, job.getStatus());
        assertEquals(100, job.getProgress().progress());
        verify(repository).save(job, ExperimentStatus.VALIDATING_RESULT);
    }

    @Test
    void validatingResultWithoutEvidenceIsRequeuedForSideEffectFreeRunners() {
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var job = expired(ExperimentStatus.VALIDATING_RESULT, "MOCK-EXTERNAL");
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));
        when(dispatch.requeueQueued(any(), any())).thenReturn(true);

        scanner(repository, dispatch, new ArtifactRegistry(temp.toString(), json), "mock").scan();

        verify(dispatch).requeueQueued(job, ExperimentStatus.VALIDATING_RESULT);
        assertEquals(ExperimentStatus.QUEUED, job.getStatus());
    }

    @Test
    void unvalidatedArtifactsAreNotTrustedForARebuild() throws Exception {
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var registry = new ArtifactRegistry(temp.toString(), json);
        var job = expired(ExperimentStatus.VALIDATING_RESULT, "MOCK-EXTERNAL");
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));
        registry.writeJson(job.getJobId(), ArtifactType.SUMMARY_JSON, "summary.json", Map.of("mock", true));
        // Never marked validated: must not be trusted for SUCCEEDED.
        when(dispatch.requeueQueued(any(), any())).thenReturn(true);

        scanner(repository, dispatch, registry, "mock").scan();

        verify(dispatch).requeueQueued(job, ExperimentStatus.VALIDATING_RESULT);
        assertNotEquals(ExperimentStatus.SUCCEEDED, job.getStatus());
    }

    @Test
    void staleObservationLosingTheRaceDoesNotKillTheScan() {
        var repository = mock(ExperimentJobRepository.class);
        var dispatch = mock(OutboxDispatchService.class);
        var job = expired(ExperimentStatus.RUNNING, "MATLAB-EXTERNAL");
        when(repository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(job));
        when(repository.save(any(), any())).thenThrow(new org.springframework.dao.OptimisticLockingFailureException("fenced"));

        assertDoesNotThrow(() ->
                scanner(repository, dispatch, new ArtifactRegistry(temp.toString(), json), "local-matlab").scan());
    }
}
