package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.runner.ProducedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarAccuracyCsvContractTest {

    @TempDir Path tempDirectory;

    @Test
    void rejectsAccuracyThatDoesNotEqualCorrectCountDividedByTrials() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ProducedArtifact> artifacts = RealPolarTestSupport.writeValidArtifacts(tempDirectory, mapper);
        Path csv = artifacts.stream().filter(a -> a.type() == ArtifactType.ACCURACY_CSV)
                .findFirst().orElseThrow().path();
        Files.writeString(csv, RealPolarTestSupport.CSV.replace(
                "32,15,0.05,8,10,0.8", "32,15,0.05,8,10,0.7"));

        ValidationResult result = new ResultValidator(mapper, new ExperimentSpecValidator())
                .validate(RealPolarTestSupport.job("JOB-CSV"),
                        RealPolarTestSupport.succeededStatus(), artifacts);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("correctCount")));
    }
}
