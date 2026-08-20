package org.example.wavepilot.template;

import org.example.wavepilot.replay.DeclarativeComparisonMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarativeReplayMetricsTest {

    @Test
    void replayMetricsComeFromTheDeclaredDefinition() {
        DeclarativeComparisonMetrics metrics =
                new DeclarativeComparisonMetrics(DeclarativeTestSupport.registryWithDemo(),
                        DeclarativeTestSupport.DEMO_TYPE_ID);

        List<org.example.wavepilot.replay.ExperimentComparisonMetrics.Metric> declared = metrics.metrics();
        assertEquals(2, declared.size());
        assertEquals("berSim", declared.get(0).name());
        assertTrue(declared.get(0).meanAlso(), "berSim declares compareMean=true");
        assertEquals("berTheory", declared.get(1).name());
        assertFalse(declared.get(1).meanAlso(), "berTheory declares compareMean=false");
    }

    @Test
    void unknownTypeIdIsRejected() {
        DeclarativeComparisonMetrics metrics =
                new DeclarativeComparisonMetrics(DeclarativeTestSupport.registryWithDemo(), "unknown-type");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, metrics::metrics);
    }
}
