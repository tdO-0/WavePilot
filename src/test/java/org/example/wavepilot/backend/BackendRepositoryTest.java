package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.mysql.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackendRepositoryTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExperimentJobMapper mapper = mock(ExperimentJobMapper.class);
    private final MySqlExperimentJobRepository repository = new MySqlExperimentJobRepository(mapper, json);

    @Test
    void uniqueKeyLoserReadsOriginalCommittedJob() throws Exception {
        var original = WavePilotTestFixtures.job("JOB-FIRST");
        var row = new ExperimentJobRow();
        row.jobId = original.getJobId(); row.idempotencyKey = "same-key";
        row.specJson = json.writeValueAsString(original.getSpec());
        row.planJson = json.writeValueAsString(original.getPlan());
        row.status = "CREATED"; row.progress = json.writeValueAsString(original.getProgress());
        row.createdAt = original.getCreatedAt(); row.updatedAt = original.getUpdatedAt();
        when(mapper.insert(any(ExperimentJobRow.class))).thenThrow(new DuplicateKeyException("unique index"));
        when(mapper.selectOne(any())).thenReturn(row);
        var loser = WavePilotTestFixtures.job("JOB-SECOND"); loser.setIdempotencyKey("same-key");
        var returned = repository.insertIfAbsent(loser);
        assertEquals("JOB-FIRST", returned.getJobId());
        assertEquals("same-key", returned.getIdempotencyKey());
        verify(mapper).insert(any(ExperimentJobRow.class));
        verify(mapper).selectOne(any());
    }

    @Test
    void unrelatedUniqueConflictIsNotSilentlySwallowed() {
        when(mapper.insert(any(ExperimentJobRow.class))).thenThrow(new DuplicateKeyException("job id collision"));
        assertThrows(DuplicateKeyException.class, () -> repository.insertIfAbsent(WavePilotTestFixtures.job("JOB-ID")));
    }

    @Test
    void failedConditionalUpdateDoesNotGrantExecution() {
        var job = WavePilotTestFixtures.job("JOB-CLAIM"); job.changeStatus(ExperimentStatus.QUEUED, "ready");
        when(mapper.claim(any())).thenReturn(0);
        assertFalse(repository.tryClaim(job));
        assertEquals(0, job.getVersion());
    }

    @Test
    void staleWriterCannotOverwriteCancellation() {
        when(mapper.updateVersioned(any(), anyString())).thenReturn(0);
        var stale = WavePilotTestFixtures.job("JOB-STALE"); stale.setVersion(3);
        assertThrows(OptimisticLockingFailureException.class, () -> repository.save(stale));
    }

    @Test
    void pinnedStatusUpdateUsesTheExpectedSourceStatus() {
        when(mapper.updateVersioned(any(), anyString())).thenReturn(1);
        var job = WavePilotTestFixtures.job("JOB-PIN"); job.changeStatus(ExperimentStatus.VALIDATED, "ready");
        repository.save(job, ExperimentStatus.CREATED);
        var argument = org.mockito.ArgumentCaptor.forClass(ExperimentJobRow.class);
        verify(mapper).updateVersioned(argument.capture(), org.mockito.ArgumentMatchers.eq("CREATED"));
        assertEquals(1, job.getVersion());
    }

    @Test
    void winningClaimBindsWorkerAndBumpsFencingToken() {
        var job = WavePilotTestFixtures.job("JOB-CLAIM-LEASE");
        job.changeStatus(ExperimentStatus.QUEUED, "ready");
        job.setWorkerId("WORKER-9");
        job.setLeaseUntil(java.time.Instant.now().plusSeconds(60));
        job.setHeartbeatAt(java.time.Instant.now());
        when(mapper.claim(any())).thenReturn(1);
        assertTrue(repository.tryClaim(job, "WORKER-9"));
        assertEquals(1, job.getAttempt());
        assertEquals(1, job.getFencingToken());
        assertEquals(1, job.getVersion());
        var row = org.mockito.ArgumentCaptor.forClass(ExperimentJobRow.class);
        verify(mapper).claim(row.capture());
        assertEquals("WORKER-9", row.getValue().workerId);
        assertNotNull(row.getValue().leaseUntil);
    }

    @Test
    void claimLoserKeepsZeroFencingAndNoExecution() {
        var job = WavePilotTestFixtures.job("JOB-CLAIM-LOSE");
        job.changeStatus(ExperimentStatus.QUEUED, "ready");
        when(mapper.claim(any())).thenReturn(0);
        assertFalse(repository.tryClaim(job, "WORKER-9"));
        assertEquals(0, job.getAttempt());
        assertEquals(0, job.getFencingToken());
    }

    @Test
    void heartbeatDelegatesToTheLeaseRenewingStatement() {
        var at = java.time.Instant.now();
        when(mapper.heartbeat(eq("JOB-HB"), eq("WORKER-9"), eq(4L), eq(at), eq(at.plusSeconds(60)))).thenReturn(1);
        assertEquals(1, repository.updateHeartbeat("JOB-HB", "WORKER-9", 4L, at, at.plusSeconds(60)));
    }

    @Test
    void requeueUsesFencedConditionalUpdateAndReturnsOutcome() {
        var job = WavePilotTestFixtures.job("JOB-REQUEUE");
        job.changeStatus(ExperimentStatus.RUNNING, "claimed");
        job.setWorkerId("DEAD-WORKER"); job.setFencingToken(3); job.setVersion(5);
        when(mapper.requeue(any(), eq("RUNNING"))).thenReturn(1);
        assertTrue(repository.requeue(job, ExperimentStatus.RUNNING, java.time.Instant.now()));
        when(mapper.requeue(any(), eq("RUNNING"))).thenReturn(0);
        assertFalse(repository.requeue(job, ExperimentStatus.RUNNING, java.time.Instant.now()));
    }

    @Test
    void staleFencingTokenCannotUpdateTheJob() {
        // The SQL predicate (worker_id <=> ? AND fencing_token = ?) rejects an old owner;
        // the repository surfaces that as an optimistic-lock failure.
        when(mapper.updateVersioned(any(), anyString())).thenReturn(0);
        var stale = WavePilotTestFixtures.job("JOB-FENCE");
        stale.changeStatus(ExperimentStatus.RUNNING, "claimed");
        stale.setWorkerId("OLD-WORKER"); stale.setFencingToken(1); stale.setVersion(4);
        assertThrows(OptimisticLockingFailureException.class,
                () -> repository.save(stale, ExperimentStatus.QUEUED));
    }

    @Test
    void decodeRestoresLeaseFields() throws Exception {
        var original = WavePilotTestFixtures.job("JOB-DECODE");
        original.setWorkerId("WORKER-9");
        original.setLeaseUntil(java.time.Instant.now().plusSeconds(30));
        original.setHeartbeatAt(java.time.Instant.now());
        original.setAttempt(2); original.setFencingToken(7);
        var row = new ExperimentJobRow();
        row.jobId = original.getJobId();
        row.specJson = json.writeValueAsString(original.getSpec());
        row.planJson = json.writeValueAsString(original.getPlan());
        row.status = "RUNNING"; row.progress = json.writeValueAsString(original.getProgress());
        row.createdAt = original.getCreatedAt(); row.updatedAt = original.getUpdatedAt();
        row.workerId = "WORKER-9"; row.leaseUntil = original.getLeaseUntil();
        row.heartbeatAt = original.getHeartbeatAt(); row.attempt = 2; row.fencingToken = 7;
        row.version = 9;
        when(mapper.selectOne(any())).thenReturn(row);
        var restored = repository.findById("JOB-DECODE").orElseThrow();
        assertEquals("WORKER-9", restored.getWorkerId());
        assertEquals(2, restored.getAttempt());
        assertEquals(7, restored.getFencingToken());
        assertEquals(9, restored.getVersion());
    }
}
