package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.lease.LeaseProperties;
import org.example.wavepilot.experiment.lease.WorkerHeartbeat;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerHeartbeatTest {

    @Test
    void heartbeatRenewsLeaseForOwnedJobsWithoutTouchingVersion() {
        var repository = mock(ExperimentJobRepository.class);
        var service = new ExperimentService(new org.example.wavepilot.experiment.validation.ExperimentSpecValidator(),
                new org.example.wavepilot.experiment.service.ExperimentStateMachine(), repository,
                mock(org.example.wavepilot.runner.ExperimentRunner.class),
                new org.example.wavepilot.artifact.ArtifactRegistry(java.nio.file.Path.of("target/heartbeat-test-artifacts").toString(), new ObjectMapper()),
                new org.example.wavepilot.experiment.validation.ResultValidator(new ObjectMapper(), new org.example.wavepilot.experiment.validation.ExperimentSpecValidator()),
                new ObjectMapper());
        var job = WavePilotTestFixtures.job("JOB-HB-OWNED");
        job.setWorkerId("WORKER-9"); job.setFencingToken(4);
        Instant claimStart = Instant.now().minusSeconds(10);
        var holders = new ConcurrentHashMap<String, ExperimentService.LeaseHolder>();
        holders.put(job.getJobId(), new ExperimentService.LeaseHolder(job, claimStart));
        ReflectionTestUtils.setField(service, "ownedLeases", holders);
        when(repository.updateHeartbeat(anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);

        var heartbeat = new WorkerHeartbeat(service, repository, new LeaseProperties(true,
                java.time.Duration.ofSeconds(60), 100, java.time.Duration.ofSeconds(5), List.of("mock")));
        heartbeat.beat();

        verify(repository).updateHeartbeat(eq("JOB-HB-OWNED"), eq("WORKER-9"), eq(4L),
                any(Instant.class), any(Instant.class));
        assertNotNull(job.getHeartbeatAt());
        assertTrue(job.getLeaseUntil().isAfter(Instant.now()));
    }
}
