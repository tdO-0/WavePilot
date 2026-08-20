package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.runner.ProducedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarAlgorithmArtifactIntegrationTest {

    @TempDir Path tempDirectory;

    @Test
    void validatedRealAlgorithmArtifactsAreRegisteredWithHashes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArtifactRegistry registry = new ArtifactRegistry(tempDirectory.toString(), mapper);
        ExperimentJob job = RealPolarTestSupport.job("JOB-ARTIFACTS");
        Path jobDirectory = registry.createJobDirectory(job.getJobId());
        List<ProducedArtifact> artifacts = RealPolarTestSupport.writeValidArtifacts(jobDirectory, mapper);

        var validation = new ResultValidator(mapper, new ExperimentSpecValidator())
                .validate(job, RealPolarTestSupport.succeededStatus(), artifacts);
        assertTrue(validation.valid(), () -> String.join("; ", validation.errors()));
        artifacts.forEach(artifact -> registry.register(job.getJobId(), artifact.type(), artifact.path()));

        List<ArtifactRecord> records = registry.listByJobId(job.getJobId());
        assertEquals(5, records.size());
        assertTrue(records.stream().allMatch(record -> record.sha256().length() == 64));
        assertTrue(records.stream().allMatch(record -> record.size() > 0));
    }
}
