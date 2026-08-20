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

class RealAlgorithmResultValidatorTest {

    @TempDir Path tempDirectory;

    @Test
    void acceptsCompleteContractAndRejectsFalseValidationClaim() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ProducedArtifact> artifacts = RealPolarTestSupport.writeValidArtifacts(tempDirectory, mapper);
        ResultValidator validator = new ResultValidator(mapper, new ExperimentSpecValidator());

        ValidationResult valid = validator.validate(RealPolarTestSupport.job("JOB-REAL-VALID"),
                RealPolarTestSupport.succeededStatus(), artifacts);
        assertTrue(valid.valid(), () -> String.join("; ", valid.errors()));

        Path summaryPath = artifacts.stream().filter(a -> a.type() == ArtifactType.SUMMARY_JSON)
                .findFirst().orElseThrow().path();
        Map<String, Object> summary = RealPolarTestSupport.validSummary();
        summary.put("algorithmValidated", true);
        mapper.writeValue(summaryPath.toFile(), summary);
        ValidationResult invalid = validator.validate(RealPolarTestSupport.job("JOB-REAL-INVALID"),
                RealPolarTestSupport.succeededStatus(), artifacts);
        assertFalse(invalid.valid());
        assertTrue(invalid.errors().stream().anyMatch(error -> error.contains("algorithmValidated")));
    }
}
