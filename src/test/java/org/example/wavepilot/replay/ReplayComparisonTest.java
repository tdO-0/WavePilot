package org.example.wavepilot.replay;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayComparisonTest {

    @TempDir Path root;

    @Test
    void fullChainReplayOfADeterministicJobIsReproducible() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("comparison"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        ReplayComparisonResult comparison = done.getComparison();
        assertNotNull(comparison);
        assertTrue(comparison.specConsistent());
        assertTrue(comparison.randomSeedConsistent());
        assertTrue(comparison.runnerTypeConsistent());
        assertTrue(comparison.templateVersionConsistent());
        assertTrue(comparison.algorithmVersionConsistent());
        assertEquals(source.getJobId(), comparison.sourceJobId());
        assertNotEquals(source.getJobId(), comparison.replayJobId());
        assertEquals(6, comparison.sourceCsvRows());
        assertEquals(6, comparison.replayCsvRows());
        assertTrue(comparison.csvRowCountConsistent());
        assertTrue(comparison.parameterGridConsistent());
        assertTrue(comparison.withinTolerance());
        assertTrue(comparison.consistent());
        assertEquals(ReplayComparisonResult.REPRODUCIBLE, comparison.verdict());

        ReplayComparisonResult.MetricComparison accuracy = metric(comparison, "accuracy");
        assertTrue(accuracy.present());
        assertEquals(0.0, accuracy.maxAbsDifference(), 0.0);
        assertEquals(0.0, accuracy.meanAbsDifference(), 0.0);
        assertTrue(accuracy.withinTolerance());
        assertNotNull(accuracy.sourceValue());
        assertNotNull(accuracy.replayValue());

        ReplayComparisonResult.MetricComparison mae = metric(comparison, "mae");
        assertTrue(mae.present());
        assertEquals(0.0, mae.maxAbsDifference(), 0.0);

        ReplayComparisonResult.MetricComparison bias = metric(comparison, "bias");
        assertTrue(bias.present());
        assertEquals(0.0, bias.maxAbsDifference(), 0.0);
    }

    @Test
    void manifestExposesEveryReplayAxis() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("manifest"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        ReplayManifest manifest = done.getManifest();
        assertNotNull(manifest);
        assertEquals(done.getReplayId(), manifest.replayId());
        assertEquals(source.getJobId(), manifest.sourceJobId());
        assertEquals(done.getReplayJobId(), manifest.replayJobId());
        assertEquals("POLAR_CODE_K_IDENTIFICATION", manifest.experimentType());
        assertEquals(source.getSpec().randomSeed(), manifest.randomSeed());
        assertEquals("deterministic-test", manifest.runnerType());
        assertEquals("polar-k-identification-simple-v1", manifest.templateVersion());
        assertEquals("polar-bsc-binomial-k-baseline", manifest.algorithmName());
        assertEquals("1.0.0", manifest.algorithmVersion());
        assertEquals("SIMPLIFIED_BASELINE", manifest.classification());
        assertEquals(false, manifest.mock());
        assertEquals(false, manifest.algorithmValidated());
        assertNotNull(manifest.matlabTemplateSha256());
        assertTrue(manifest.matlabTemplateSha256().length() >= 32);
        assertNotNull(manifest.javaApplicationVersion());
        assertTrue(manifest.replayFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(manifest.canonicalExperimentSpec().contains("randomSeed"));
        assertNotNull(manifest.createdAt());
    }

    private ReplayComparisonResult.MetricComparison metric(ReplayComparisonResult comparison, String name) {
        return comparison.metrics().stream()
                .filter(metric -> metric.metricName().equals(name)).findFirst().orElseThrow();
    }
}
