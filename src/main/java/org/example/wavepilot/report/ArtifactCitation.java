package org.example.wavepilot.report;

import org.example.wavepilot.artifact.ArtifactType;

public record ArtifactCitation(
        String citationId,
        String jobId,
        String artifactId,
        ArtifactType artifactType,
        String fieldName,
        String rowReference,
        Object value,
        String unit,
        String description,
        String artifactSha256) {
}
