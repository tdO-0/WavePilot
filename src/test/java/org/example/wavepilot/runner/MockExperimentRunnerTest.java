package org.example.wavepilot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockExperimentRunnerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void asynchronouslyGeneratesDeterministicInspectableArtifacts() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(tempDirectory.toString(), objectMapper);
        MockExperimentRunner runner = new MockExperimentRunner(registry, objectMapper,
                new ExperimentSpecValidator());
        try {
            ExperimentJob job = WavePilotTestFixtures.job("JOB-MOCK-1");
            RunnerSubmission submission = runner.submit(job);
            RunnerStatus status = waitUntilTerminal(runner, submission.externalJobId());

            assertEquals(RunnerStatus.State.SUCCEEDED, status.state());
            assertEquals(0, status.exitCode());
            List<ProducedArtifact> artifacts = runner.collectArtifacts(submission.externalJobId());
            assertEquals(3, artifacts.size());
            assertTrue(artifacts.stream().anyMatch(a -> a.type() == ArtifactType.ACCURACY_CSV));
            assertTrue(artifacts.stream().allMatch(a -> Files.isRegularFile(a.path())));
            assertTrue(Files.readString(tempDirectory.resolve("JOB-MOCK-1/summary.json")).contains("\"mock\" : true"));
        } finally {
            runner.shutdown();
        }
    }

    private RunnerStatus waitUntilTerminal(MockExperimentRunner runner, String externalJobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        RunnerStatus status;
        do {
            status = runner.getStatus(externalJobId);
            if (status.terminal()) return status;
            Thread.sleep(10);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Mock runner did not finish within timeout");
    }
}
