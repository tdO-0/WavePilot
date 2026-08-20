package org.example.wavepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.runner.MockExperimentRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Phase2RegressionTest {

    @TempDir
    Path tempDirectory;

    @Test
    void structuredSpecStillRunsThroughOriginalPhase2MockPipeline() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExperimentSpecValidator validator = new ExperimentSpecValidator();
        ArtifactRegistry artifacts = new ArtifactRegistry(tempDirectory.toString(), mapper);
        MockExperimentRunner runner = new MockExperimentRunner(artifacts, mapper, validator);
        ExperimentService service = new ExperimentService(validator, new ExperimentStateMachine(),
                new InMemoryExperimentJobRepository(), runner, artifacts,
                new ResultValidator(mapper, validator), mapper);
        try {
            ExperimentJob job = service.create(WavePilotTestFixtures.validSpec());
            Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
            while (!job.getStatus().isTerminal() && Instant.now().isBefore(deadline)) Thread.sleep(10);

            assertEquals(ExperimentStatus.SUCCEEDED, job.getStatus(), job.getFailureReason());
            assertEquals(5, service.artifacts(job.getJobId()).size());
        } finally {
            service.shutdown();
            runner.shutdown();
        }
    }
}
