package org.example.wavepilot.replay;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards that the Phase 5C replay chain survives the Eval phase untouched. */
class ReplayRegressionTest {

    @TempDir Path root;

    @Test
    void fullChainReplayStillJudgesReproducible() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("regression"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        assertNotNull(done.getComparison());
        assertEquals(ReplayComparisonResult.REPRODUCIBLE, done.getComparison().verdict());
        assertTrue(done.getComparison().withinTolerance());
    }

    @Test
    void fingerprintRemainsDeterministic() {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        var input = new org.example.wavepilot.evaluation.ReplayFingerprintInput(
                org.example.wavepilot.WavePilotTestFixtures.validSpec(),
                "polar-k-identification-simple-v1", 20L, "digest",
                java.util.Map.of("runnerType", "local-matlab"), java.util.List.of("h1"));
        assertEquals(stack.fingerprints().fingerprint(input), stack.fingerprints().fingerprint(input));
    }

    @Test
    void replayPackageClassesStillExposeTheRequiredSurface() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.replay.ReplayService"));
        assertNotNull(Class.forName("org.example.wavepilot.replay.ReplayManifest"));
        assertNotNull(Class.forName("org.example.wavepilot.replay.ReplayComparisonResult"));
        assertNotNull(Class.forName("org.example.wavepilot.replay.ReplayController"));
        assertNotNull(org.example.wavepilot.artifact.ArtifactType.valueOf("REPLAY_MANIFEST"));
        assertNotNull(org.example.wavepilot.artifact.ArtifactType.valueOf("REPLAY_COMPARISON"));
    }
}
