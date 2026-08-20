package org.example.wavepilot.report;

import org.example.wavepilot.experiment.model.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportCitationValidatorTest {
    @TempDir Path root;

    @Test void verifiesCompleteCitationSetAndRejectsMissingCsvField() throws Exception {
        ReportTestSupport.Fixture fixture = ReportTestSupport.fixture(root);
        ExperimentReportData data = fixture.data();
        assertTrue(fixture.validator().validate(data.jobId(), data).valid());

        ArrayList<ArtifactCitation> changed = new ArrayList<>(data.citations());
        ArtifactCitation original = changed.stream().filter(c -> c.artifactType().name().equals("ACCURACY_CSV"))
                .findFirst().orElseThrow();
        changed.set(changed.indexOf(original), new ArtifactCitation(original.citationId(), original.jobId(),
                original.artifactId(), original.artifactType(), "notAField", original.rowReference(),
                original.value(), original.unit(), original.description(), original.artifactSha256()));
        ValidationResult result = fixture.validator().validate(data.jobId(),
                ReportTestSupport.withCitations(data, changed));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("fieldName")));

        changed = new ArrayList<>(data.citations());
        original = changed.stream().filter(c -> c.artifactType().name().equals("ACCURACY_CSV"))
                .findFirst().orElseThrow();
        changed.set(changed.indexOf(original), new ArtifactCitation(original.citationId(), original.jobId(),
                original.artifactId(), original.artifactType(), original.fieldName(), "999",
                original.value(), original.unit(), original.description(), original.artifactSha256()));
        result = fixture.validator().validate(data.jobId(), ReportTestSupport.withCitations(data, changed));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("rowReference")));
    }
}
