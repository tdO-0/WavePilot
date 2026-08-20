package org.example.wavepilot.report;

import org.example.wavepilot.artifact.ArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ArtifactHashMismatchTest {
    @TempDir Path root;

    @Test void rejectsCsvModifiedAfterRegistration() throws Exception {
        ReportTestSupport.Fixture fixture = ReportTestSupport.fixture(root);
        ExperimentReportData data = fixture.data();
        String id = fixture.artifacts().stream().filter(a -> a.type() == ArtifactType.ACCURACY_CSV)
                .findFirst().orElseThrow().artifactId();
        Files.writeString(fixture.registry().resolveVerified(id), ReportTestSupport.CSV + "\n");
        assertFalse(fixture.validator().validate(data.jobId(), data).valid());
    }
}
