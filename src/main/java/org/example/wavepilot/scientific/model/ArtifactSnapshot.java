package org.example.wavepilot.scientific.model;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;

public record ArtifactSnapshot(
        String artifactId,
        ArtifactType type,
        String relativePath,
        String sha256,
        long size,
        boolean validated) {
    public static ArtifactSnapshot from(ArtifactRecord record) {
        return new ArtifactSnapshot(record.artifactId(), record.type(), record.relativePath(),
                record.sha256(), record.size(), record.validated());
    }
}
