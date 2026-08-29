package org.example.wavepilot.knowledge.model;

import org.example.wavepilot.experiment.model.ExperimentType;

import java.util.Map;

public record KnowledgeSearchResult(
        String chunkId,
        String documentId,
        String title,
        String source,
        String content,
        double similarityScore,
        DocumentType documentType,
        ExperimentType experimentType,
        String citation,
        String section,
        Map<String, String> metadata,
        String retrievalMethod) {

    /** Compatibility constructor for existing clients and tests. */
    public KnowledgeSearchResult(String chunkId, String documentId, String title, String source,
                                 String content, double similarityScore, DocumentType documentType,
                                 ExperimentType experimentType, String citation) {
        this(chunkId, documentId, title, source, content, similarityScore, documentType,
                experimentType, citation, "", Map.of(), "DENSE");
    }

    public KnowledgeSearchResult {
        section = section == null ? "" : section;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        retrievalMethod = retrievalMethod == null ? "UNKNOWN" : retrievalMethod;
    }

    public static KnowledgeSearchResult from(KnowledgeChunk chunk, double similarityScore) {
        KnowledgeDocumentMetadata metadata = chunk.metadata();
        return new KnowledgeSearchResult(chunk.chunkId(), metadata.documentId(), metadata.title(),
                metadata.source(), chunk.content(), similarityScore, metadata.documentType(),
                metadata.experimentType(), chunk.citation(), chunk.section(), chunk.attributes(), "DENSE");
    }

    public KnowledgeSearchResult withScoreAndMethod(double score, String method) {
        return new KnowledgeSearchResult(chunkId, documentId, title, source, content, score,
                documentType, experimentType, citation, section, metadata, method);
    }
}
