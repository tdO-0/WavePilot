package org.example.wavepilot.experiment.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jobs persisted with wavepilot.jobs.persistence=file must survive a repository restart. */
class FileSystemExperimentJobRepositoryTest {

    @TempDir
    Path tempDir;

    private FileSystemExperimentJobRepository repository() throws Exception {
        // findAndRegisterModules picks up JavaTimeModule (Instant) like Spring Boot does.
        return new FileSystemExperimentJobRepository(tempDir.toString(),
                new ObjectMapper().findAndRegisterModules());
    }

    private ExperimentJob sampleJob(String jobId) {
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64), 0.0, 0.02, 0.01, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV), "persistence test");
        ExperimentPlan plan = new ExperimentPlan("PLAN-1", spec, "polar-k-identification-simple-v1",
                4, List.of("VALIDATE_SPEC", "RUN_EXPERIMENT"), Instant.now());
        return new ExperimentJob(jobId, spec, plan);
    }

    @Test
    void jobSurvivesRepositoryRecreationWithFinalState() throws Exception {
        FileSystemExperimentJobRepository first = repository();
        ExperimentJob job = sampleJob("JOB-PERSIST-1");
        first.save(job);
        job.changeStatus(ExperimentStatus.SUCCEEDED, "runner succeeded");
        first.save(job);

        // Simulate an application restart: a fresh repository reads the same directory.
        FileSystemExperimentJobRepository second = repository();
        Optional<ExperimentJob> restored = second.findById("JOB-PERSIST-1");
        assertTrue(restored.isPresent(), "job must survive restart");
        assertEquals("JOB-PERSIST-1", restored.get().getJobId());
        assertEquals(ExperimentStatus.SUCCEEDED, restored.get().getStatus(),
                "final status must survive restart");
        assertEquals("polar-k-identification-simple-v1",
                restored.get().getPlan().experimentTemplateVersion());
        assertEquals(2, restored.get().getSpec().codeLengths().size());
    }

    @Test
    void allJobsAreListedAfterRestart() throws Exception {
        FileSystemExperimentJobRepository first = repository();
        first.save(sampleJob("JOB-PERSIST-2"));
        first.save(sampleJob("JOB-PERSIST-3"));

        FileSystemExperimentJobRepository second = repository();
        assertEquals(2, second.findAll().size(), "all persisted jobs must be listed");
    }

    @Test
    void failedJobsKeepTheirFailureReason() throws Exception {
        FileSystemExperimentJobRepository first = repository();
        ExperimentJob job = sampleJob("JOB-PERSIST-4");
        first.save(job);
        job.setFailureReason("runner crashed");
        job.changeStatus(ExperimentStatus.FAILED, "runner crashed");
        first.save(job);

        ExperimentJob restored = repository().findById("JOB-PERSIST-4").orElseThrow();
        assertEquals(ExperimentStatus.FAILED, restored.getStatus());
        assertEquals("runner crashed", restored.getFailureReason());
    }

    @Test
    void savedFilesLiveUnderTheConfiguredDirectory() throws Exception {
        FileSystemExperimentJobRepository first = repository();
        first.save(sampleJob("JOB-PERSIST-5"));
        assertNotNull(first.findById("JOB-PERSIST-5"));
        Path file = tempDir.resolve("JOB-PERSIST-5.json");
        assertTrue(file.toFile().exists(), "job snapshot must be written to disk");
    }
}
