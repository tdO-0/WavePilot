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
        when(mapper.updateVersioned(any())).thenReturn(0);
        var stale = WavePilotTestFixtures.job("JOB-STALE"); stale.setVersion(3);
        assertThrows(OptimisticLockingFailureException.class, () -> repository.save(stale));
    }
}
