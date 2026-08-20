package org.example.wavepilot.knowledge.model;

import org.example.wavepilot.experiment.model.ExperimentType;

public record KnowledgeSearchResult(
        String chunkId,
        String documentId,
        String title,
        String source,
        String content,
        double similarityScore,
        DocumentType documentType,
        ExperimentType experimentType,
        String citation) {

    public static KnowledgeSearchResult from(KnowledgeChunk chunk, double similarityScore) {
        KnowledgeDocumentMetadata metadata = chunk.metadata();
        return new KnowledgeSearchResult(chunk.chunkId(), metadata.documentId(), metadata.title(),
                metadata.source(), chunk.content(), similarityScore, metadata.documentType(),
                metadata.experimentType(), chunk.citation());
    }
}
