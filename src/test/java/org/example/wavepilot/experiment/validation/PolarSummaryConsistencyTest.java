package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.runner.ProducedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarSummaryConsistencyTest {

    @TempDir Path tempDirectory;

    @Test
    void rejectsSummaryMeanThatDoesNotMatchCsv() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ProducedArtifact> artifacts = RealPolarTestSupport.writeValidArtifacts(tempDirectory, mapper);
        Path summaryPath = artifacts.stream().filter(a -> a.type() == ArtifactType.SUMMARY_JSON)
                .findFirst().orElseThrow().path();
        Map<String, Object> summary = RealPolarTestSupport.validSummary();
        summary.put("meanAccuracy", 0.123);
        mapper.writeValue(summaryPath.toFile(), summary);

        ValidationResult result = new ResultValidator(mapper, new ExperimentSpecValidator())
                .validate(RealPolarTestSupport.job("JOB-SUMMARY"),
                        RealPolarTestSupport.succeededStatus(), artifacts);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("meanAccuracy")));
    }
}
