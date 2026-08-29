package org.example.wavepilot.scientific.ledger;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;

public record LedgerArtifactReference(
        String artifactId,
        ArtifactType artifactType,
        String relativePath,
        String sha256,
        long size,
        boolean validated) {
    public static LedgerArtifactReference from(ArtifactRecord record) {
        return new LedgerArtifactReference(record.artifactId(), record.type(), record.relativePath(),
                record.sha256(), record.size(), record.validated());
    }
}
