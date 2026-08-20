package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReportDataAssemblerTest {
    @TempDir Path root;

    @Test void extractsMetricsTrendsAndArtifactMetadata() throws Exception {
        ExperimentReportData data = ReportTestSupport.fixture(root).data();
        assertEquals("polar-bsc-binomial-k-baseline", data.algorithmName());
        assertEquals("SIMPLIFIED_BASELINE", data.classification());
        assertFalse(data.mock());
        assertFalse(data.algorithmValidated());
        assertEquals(6, data.accuracyPoints().size());
        assertEquals(2, data.codeLengthTrends().size());
        assertEquals(0.5, data.accuracySummary().minAccuracy());
        assertEquals(5.0 / 6.0, data.accuracySummary().meanAccuracy(), 1e-9);
        assertFalse(data.citations().isEmpty());
        data.artifacts().forEach(artifact -> assertFalse(artifact.relativePath().contains(":")));
    }
}
