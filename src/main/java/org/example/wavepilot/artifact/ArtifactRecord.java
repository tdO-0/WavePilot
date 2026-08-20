package org.example.wavepilot.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ArtifactRecord(
        String artifactId,
        String jobId,
        @JsonProperty("artifactType") ArtifactType type,
        String fileName,
        String runnerType,
        boolean mock,
        boolean algorithmValidated,
        String classification,
        String relativePath,
        String sha256,
        long size,
        String mimeType,
        String templateVersion,
        String algorithmVersion,
        boolean validated,
        Instant createdAt,
        @JsonIgnore
        String path) {

    @JsonIgnore
    public ArtifactType artifactType() {
        return type;
    }
}
