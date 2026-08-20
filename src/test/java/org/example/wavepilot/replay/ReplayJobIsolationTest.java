package org.example.wavepilot.replay;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayJobIsolationTest {

    @TempDir Path root;

    @Test
    void replayCreatesAnIndependentJobAndNeverReusesTheSourceDirectory() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        List<ArtifactRecord> sourceArtifactsBefore = stack.registry().listByJobId(source.getJobId());

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("isolation"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        String replayJobId = done.getReplayJobId();
        assertNotNull(replayJobId);
        assertNotEquals(source.getJobId(), replayJobId, "replay must run in a new job");

        ExperimentJob replay = stack.experimentService().get(replayJobId);
        assertEquals(ExperimentStatus.SUCCEEDED, replay.getStatus());
        assertEquals(source.getSpec().randomSeed(), replay.getSpec().randomSeed(),
                "replay must preserve the randomSeed");
        assertEquals(source.getJobId(), replay.getSourceJobId(),
                "replay job must reference the source job");

        List<ArtifactRecord> sourceArtifactsAfter = stack.registry().listByJobId(source.getJobId());
        assertEquals(sourceArtifactsBefore.size(), sourceArtifactsAfter.size(),
                "source job artifacts must be untouched");
        assertTrue(sourceArtifactsAfter.stream().noneMatch(item ->
                        item.type() == ArtifactType.REPLAY_MANIFEST
                                || item.type() == ArtifactType.REPLAY_COMPARISON),
                "replay bookkeeping must not be written into the source job directory");

        List<ArtifactRecord> replayArtifacts = stack.registry().listByJobId(replayJobId);
        assertTrue(replayArtifacts.stream().anyMatch(item ->
                item.type() == ArtifactType.ACCURACY_CSV), "replay job must have its own CSV");
        assertNotEquals(sourceArtifactsBefore.stream()
                        .filter(item -> item.type() == ArtifactType.ACCURACY_CSV).findFirst()
                        .orElseThrow().artifactId(),
                replayArtifacts.stream().filter(item -> item.type() == ArtifactType.ACCURACY_CSV)
                        .findFirst().orElseThrow().artifactId(),
                "replay must produce its own artifact ids");
    }
}
