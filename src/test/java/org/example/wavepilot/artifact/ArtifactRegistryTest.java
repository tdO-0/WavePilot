package org.example.wavepilot.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactRegistryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void registersSizeAndSha256() {
        ArtifactRegistry registry = new ArtifactRegistry(tempDirectory.toString(), new ObjectMapper());

        ArtifactRecord record = registry.writeJson("JOB-ART-1", ArtifactType.EXPERIMENT_SPEC,
                "experiment-spec.json", Map.of("safe", true));

        assertEquals(64, record.sha256().length());
        assertTrue(record.size() > 0);
        assertEquals(1, registry.listByJobId("JOB-ART-1").size());
    }

    @Test
    void rejectsDirectoryTraversalAndOutsideFiles() throws Exception {
        ArtifactRegistry registry = new ArtifactRegistry(tempDirectory.resolve("root").toString(), new ObjectMapper());
        assertThrows(ArtifactRegistry.ArtifactStorageException.class,
                () -> registry.createJobDirectory("../escape"));

        Path outside = tempDirectory.resolve("outside.csv");
        Files.writeString(outside, "codeLength,errorRate,accuracy");
        assertThrows(ArtifactRegistry.ArtifactStorageException.class,
                () -> registry.register("JOB-ART-2", ArtifactType.ACCURACY_CSV, outside));
    }
}
