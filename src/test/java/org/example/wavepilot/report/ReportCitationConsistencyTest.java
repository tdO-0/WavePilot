package org.example.wavepilot.report;

import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportCitationConsistencyTest {

    @TempDir java.nio.file.Path tempDirectory;

    private ReportCitationValidator validator() {
        ObjectMapper mapper = new ObjectMapper();
        return new ReportCitationValidator(new ArtifactRegistry(tempDirectory.toString(), mapper), mapper);
    }

    @Test
    void acceptsNumericConclusionWithArtifactAndFieldReference() {
        ExperimentReport report = new ExperimentReport("JOB-REPORT-1", ExperimentStatus.SUCCEEDED,
                "validated report",
                List.of(new ExperimentReport.Conclusion(
                        "N=32 and errorRate=0.20 has the referenced accuracy", 0.509,
                        "ART-003", "accuracy", "codeLength=32,errorRate=0.20")), Instant.now());

        assertTrue(validator().validate(report).valid());
    }

    @Test
    void rejectsUntraceableNumericConclusion() {
        ExperimentReport report = new ExperimentReport("JOB-REPORT-2", ExperimentStatus.SUCCEEDED,
                "unsafe report",
                List.of(new ExperimentReport.Conclusion("unreferenced accuracy", 0.999,
                        null, null, null)), Instant.now());

        ValidationResult result = validator().validate(report);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("artifactId")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("fieldName")));
    }
}
