package org.example.wavepilot.knowledge.model;

import org.example.wavepilot.experiment.model.ExperimentType;

import java.time.Instant;

public record KnowledgeDocumentMetadata(
        String documentId,
        DocumentType documentType,
        ExperimentType experimentType,
        String title,
        String source,
        String version,
        Instant createdAt) {

    public KnowledgeDocumentMetadata {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId is required");
        if (documentType == null) throw new IllegalArgumentException("documentType is required");
        if (experimentType == null) throw new IllegalArgumentException("experimentType is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
