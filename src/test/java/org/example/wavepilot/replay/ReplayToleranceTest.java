package org.example.wavepilot.replay;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayToleranceTest {

    @TempDir Path root;

    @Test
    void valuesWithinToleranceAreReproducible() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("tight"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        assertTrue(done.getComparison().withinTolerance());
        assertEquals(ReplayComparisonResult.REPRODUCIBLE, done.getComparison().verdict());
    }

    @Test
    void driftBeyondTheToleranceIsNotReproducible() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        stack.runner().setAccuracyOffset(0.1);

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("drift"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        ReplayComparisonResult comparison = done.getComparison();
        assertFalse(comparison.withinTolerance());
        assertEquals(ReplayComparisonResult.NOT_REPRODUCIBLE, comparison.verdict());
        assertTrue(comparison.consistent(), "strict axes still match; only the numeric metric drifted");
        ReplayComparisonResult.MetricComparison accuracy = comparison.metrics().stream()
                .filter(metric -> metric.metricName().equals("accuracy")).findFirst().orElseThrow();
        assertTrue(accuracy.present());
        assertTrue(accuracy.maxAbsDifference() > 1.0e-9);
        assertFalse(accuracy.withinTolerance());
        assertTrue(comparison.message().contains("accuracy"));
    }

    @Test
    void aLooseToleranceHonorsTheConfiguredBoundary() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root, 0.2);
        ExperimentJob source = ReplayTestSupport.createSucceededJob(stack);
        stack.runner().setAccuracyOffset(0.1);

        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("loose"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        ReplayComparisonResult comparison = done.getComparison();
        assertTrue(comparison.withinTolerance(), "max abs diff fits the configured tolerance");
        assertEquals(ReplayComparisonResult.REPRODUCIBLE, comparison.verdict());
        ReplayComparisonResult.MetricComparison accuracy = comparison.metrics().stream()
                .filter(metric -> metric.metricName().equals("accuracy")).findFirst().orElseThrow();
        assertTrue(accuracy.maxAbsDifference() > 0.0, "values genuinely differ");
        assertTrue(accuracy.maxAbsDifference() <= 0.2);
    }
}
