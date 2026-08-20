package org.example.wavepilot.replay;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySourceJobValidationTest {

    @TempDir Path root;

    @Test
    void unknownSourceJobIsRejected() {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        assertThrows(NoSuchElementException.class,
                () -> stack.replayService().startReplay("JOB-NOPE", new ReplayRequest("x")));
    }

    @Test
    void nonSucceededSourceJobIsRejected() {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ReplayTestSupport.directJob(stack, "JOB-NOT-DONE");

        ReplayService.ReplayValidationException exception = assertThrows(
                ReplayService.ReplayValidationException.class,
                () -> stack.replayService().startReplay("JOB-NOT-DONE", new ReplayRequest("x")));
        assertTrue(exception.getMessage().contains("SUCCEEDED"));
    }

    @Test
    void sourceJobWithoutValidatedArtifactsIsRejected() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob job = ReplayTestSupport.directJob(stack, "JOB-NO-VALIDATION");
        job.changeStatus(ExperimentStatus.SUCCEEDED, "fixture");
        Path csv = stack.registry().createJobDirectory(job.getJobId()).resolve("accuracy.csv");
        Files.writeString(csv, ReplayTestSupport.FIXTURE_CSV);
        stack.registry().register(job.getJobId(), ArtifactType.ACCURACY_CSV, csv);
        stack.registry().writeJson(job.getJobId(), ArtifactType.SUMMARY_JSON, "summary.json",
                ReplayTestSupport.realFormatSummary(job.getSpec(),
                        List.of(ReplayTestSupport.FIXTURE_CSV.split("\n"))));

        ReplayService.ReplayValidationException exception = assertThrows(
                ReplayService.ReplayValidationException.class,
                () -> stack.replayService().startReplay("JOB-NO-VALIDATION", new ReplayRequest("x")));
        assertTrue(exception.getMessage().contains("ResultValidator"));
    }

    @Test
    void sourceJobWithTamperedArtifactHashIsRejected() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ReplayTestSupport.directSucceededJob(stack, "JOB-TAMPERED", ReplayTestSupport.FIXTURE_CSV,
                ReplayTestSupport.realFormatSummary(WavePilotTestFixtures.validSpec(),
                        List.of(ReplayTestSupport.FIXTURE_CSV.split("\n"))));
        ArtifactRecord csv = stack.registry().listByJobId("JOB-TAMPERED").stream()
                .filter(record -> record.type() == ArtifactType.ACCURACY_CSV).findFirst().orElseThrow();
        Path verified = stack.registry().resolveVerified(csv.artifactId());
        Files.writeString(verified, "32,15,0,1,10,0.1,50,20,15,0,0,0.01,1.0.0\n",
                StandardCharsets.UTF_8);

        ReplayService.ReplayValidationException exception = assertThrows(
                ReplayService.ReplayValidationException.class,
                () -> stack.replayService().startReplay("JOB-TAMPERED", new ReplayRequest("x")));
        assertTrue(exception.getMessage().contains("hash or size changed"));
    }

    @Test
    void missingKeyArtifactIsRejected() {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob bare = ReplayTestSupport.directJob(stack, "JOB-BARE");
        bare.changeStatus(ExperimentStatus.SUCCEEDED, "fixture");

        ReplayService.ReplayValidationException exception = assertThrows(
                ReplayService.ReplayValidationException.class,
                () -> stack.replayService().startReplay("JOB-BARE", new ReplayRequest("x")));
        assertTrue(exception.getMessage().contains("missing key artifacts"));
        assertEquals(ExperimentStatus.SUCCEEDED, bare.getStatus());
    }

    @Test
    void validationFailureDoesNotCreateAnyReplay() {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        assertThrows(NoSuchElementException.class,
                () -> stack.replayService().startReplay("JOB-NOPE", new ReplayRequest("x")));
        assertTrue(stack.replayRepository().findAll().isEmpty());
    }
}
