package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CrossJobCitationRejectedTest {
    @TempDir Path root;

    @Test void rejectsCrossJobCitation() throws Exception {
        ReportTestSupport.Fixture fixture = ReportTestSupport.fixture(root);
        ExperimentReportData data = fixture.data();
        ArrayList<ArtifactCitation> changed = new ArrayList<>(data.citations());
        ArtifactCitation c = changed.get(0);
        changed.set(0, new ArtifactCitation(c.citationId(), "JOB-OTHER", c.artifactId(), c.artifactType(),
                c.fieldName(), c.rowReference(), c.value(), c.unit(), c.description(), c.artifactSha256()));
        assertFalse(fixture.validator().validate(data.jobId(),
                ReportTestSupport.withCitations(data, changed)).valid());
    }
}
