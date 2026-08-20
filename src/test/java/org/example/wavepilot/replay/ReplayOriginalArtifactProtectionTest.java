package org.example.wavepilot.replay;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayOriginalArtifactProtectionTest {

    @TempDir Path root;

    @Test
    void replayNeverOverwritesSourceArtifacts() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        ArtifactRecord sourceCsv = artifact(stack, source.getJobId(), ArtifactType.ACCURACY_CSV);
        ArtifactRecord sourceSummary = artifact(stack, source.getJobId(), ArtifactType.SUMMARY_JSON);
        byte[] csvBefore = Files.readAllBytes(stack.registry().resolveVerified(sourceCsv.artifactId()));
        byte[] summaryBefore = Files.readAllBytes(stack.registry().resolveVerified(sourceSummary.artifactId()));

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("protect"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());
        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());

        ArtifactRecord csvAfter = artifact(stack, source.getJobId(), ArtifactType.ACCURACY_CSV);
        ArtifactRecord summaryAfter = artifact(stack, source.getJobId(), ArtifactType.SUMMARY_JSON);
        assertEquals(sourceCsv.sha256(), csvAfter.sha256(), "source CSV SHA-256 must not change");
        assertEquals(sourceSummary.sha256(), summaryAfter.sha256(), "source summary SHA-256 must not change");
        assertEquals(sourceCsv.size(), csvAfter.size(), "source CSV size must not change");
        assertTrue(stack.registry().verify(sourceCsv.artifactId()), "source CSV must still verify");
        assertTrue(stack.registry().verify(sourceSummary.artifactId()), "source summary must still verify");
        assertArrayEquals(csvBefore, Files.readAllBytes(stack.registry().resolveVerified(csvAfter.artifactId())),
                "source CSV content must be byte-identical");
        assertArrayEquals(summaryBefore,
                Files.readAllBytes(stack.registry().resolveVerified(summaryAfter.artifactId())),
                "source summary content must be byte-identical");
    }

    @Test
    void replayJobArtifactsLiveInTheirOwnDirectory() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("protect"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        List<ArtifactRecord> sourceArtifacts = stack.registry().listByJobId(source.getJobId());
        List<ArtifactRecord> replayArtifacts = stack.registry().listByJobId(done.getReplayJobId());
        for (ArtifactRecord sourceArtifact : sourceArtifacts) {
            assertTrue(replayArtifacts.stream().noneMatch(replayArtifact ->
                    replayArtifact.relativePath().equals(sourceArtifact.relativePath())),
                    "replay directory must not shadow any source artifact path");
        }
    }

    private ArtifactRecord artifact(ReplayTestSupport.Stack stack, String jobId, ArtifactType type) {
        return stack.registry().listByJobId(jobId).stream()
                .filter(record -> record.type() == type).findFirst().orElseThrow();
    }
}
