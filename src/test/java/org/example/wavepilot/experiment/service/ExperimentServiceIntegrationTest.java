package org.example.wavepilot.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.runner.MockExperimentRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExperimentServiceIntegrationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void runsValidatedSpecAsynchronouslyAndRegistersFiveArtifacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator();
        ArtifactRegistry registry = new ArtifactRegistry(tempDirectory.toString(), mapper);
        MockExperimentRunner runner = new MockExperimentRunner(registry, mapper, specValidator);
        ExperimentService service = new ExperimentService(specValidator, new ExperimentStateMachine(),
                new InMemoryExperimentJobRepository(), runner, registry,
                new ResultValidator(mapper, specValidator), mapper);
        try {
            ExperimentJob job = service.create(WavePilotTestFixtures.validSpec());
            Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
            while (!job.getStatus().isTerminal() && Instant.now().isBefore(deadline)) {
                Thread.sleep(10);
            }

            assertEquals(ExperimentStatus.SUCCEEDED, job.getStatus(), job.getFailureReason());
            assertNull(job.getFailureReason());
            assertEquals(5, service.artifacts(job.getJobId()).size());
        } finally {
            service.shutdown();
            runner.shutdown();
        }
    }
}
