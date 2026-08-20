package org.example.wavepilot.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.evaluation.ReplayFingerprintInput;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayArtifactRegistrationTest {

    @TempDir Path root;

    @Test
    void successfulReplayRegistersManifestAndComparisonArtifacts() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("artifacts"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        List<ArtifactRecord> records = stack.registry().listByJobId(done.getReplayJobId());
        ArtifactRecord manifestRecord = records.stream()
                .filter(item -> item.type() == ArtifactType.REPLAY_MANIFEST).findFirst().orElseThrow();
        ArtifactRecord comparisonRecord = records.stream()
                .filter(item -> item.type() == ArtifactType.REPLAY_COMPARISON).findFirst().orElseThrow();
        assertTrue(manifestRecord.validated(), "manifest must be part of the accepted artifact set");
        assertTrue(comparisonRecord.validated(), "comparison must be part of the accepted artifact set");

        ObjectMapper mapper = stack.mapper();
        ReplayManifest manifest = mapper.readValue(
                stack.registry().resolveVerified(manifestRecord.artifactId()).toFile(), ReplayManifest.class);
        assertEquals(done.getReplayId(), manifest.replayId());
        assertEquals(source.getJobId(), manifest.sourceJobId());
        assertEquals(done.getReplayJobId(), manifest.replayJobId());
        assertEquals(20L, manifest.randomSeed());
        assertEquals("SIMPLIFIED_BASELINE", manifest.classification());
        assertEquals(false, manifest.mock());
        assertEquals(false, manifest.algorithmValidated());
        assertEquals(expectedFingerprint(stack, source), manifest.replayFingerprint());
        assertNotNull(manifest.matlabTemplateSha256());
        assertNotNull(manifest.javaApplicationVersion());
        assertTrue(manifest.canonicalExperimentSpec().contains("\"randomSeed\":20"));

        ReplayComparisonResult comparison = mapper.readValue(
                stack.registry().resolveVerified(comparisonRecord.artifactId()).toFile(),
                ReplayComparisonResult.class);
        assertEquals(ReplayComparisonResult.REPRODUCIBLE, comparison.verdict());
        assertEquals(done.getReplayId(), comparison.replayId());
    }

    @Test
    void everySuccessfulReplayRegistersBothBookkeepingArtifacts() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        stack.runner().setAccuracyOffset(0.5);
        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("offset-runner"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        // A drift still satisfying the validator contract yields a succeeded replay, proving
        // both bookkeeping artifacts are registered and the comparison is always available.
        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        assertEquals(ReplayComparisonResult.NOT_REPRODUCIBLE, done.getComparison().verdict());
        List<ArtifactRecord> records = stack.registry().listByJobId(done.getReplayJobId());
        assertTrue(records.stream().anyMatch(item -> item.type() == ArtifactType.REPLAY_MANIFEST));
        assertTrue(records.stream().anyMatch(item -> item.type() == ArtifactType.REPLAY_COMPARISON));
        assertNotNull(done.getComparison());
    }

    private String expectedFingerprint(ReplayTestSupport.Stack stack, ExperimentJob source) {
        ArtifactRecord csv = stack.registry().listByJobId(source.getJobId()).stream()
                .filter(record -> record.type() == ArtifactType.ACCURACY_CSV).findFirst().orElseThrow();
        List<String> keyHashes = stack.registry().listByJobId(source.getJobId()).stream()
                .filter(record -> record.type() == ArtifactType.EXPERIMENT_SPEC
                        || record.type() == ArtifactType.EXPERIMENT_PLAN
                        || record.type() == ArtifactType.ACCURACY_CSV
                        || record.type() == ArtifactType.SUMMARY_JSON)
                .map(ArtifactRecord::sha256).toList();
        ReplayFingerprintInput input = new ReplayFingerprintInput(
                source.getSpec(), "polar-k-identification-simple-v1", source.getSpec().randomSeed(),
                new MatlabTemplateDigest().compute("polar-k-identification-simple-v1"),
                Map.of("runnerType", csv.runnerType(),
                        "algorithmName", "polar-bsc-binomial-k-baseline",
                        "algorithmVersion", "1.0.0",
                        "classification", "SIMPLIFIED_BASELINE"),
                keyHashes);
        return stack.fingerprints().fingerprint(input);
    }
}
