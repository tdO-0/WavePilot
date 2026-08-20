package org.example.wavepilot.knowledge.model;

public record KnowledgeChunk(
        String chunkId,
        KnowledgeDocumentMetadata metadata,
        String content) {

    public KnowledgeChunk {
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("chunkId is required");
        if (metadata == null) throw new IllegalArgumentException("metadata is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
    }

    public String citation() {
        return "KB[" + metadata.documentId() + "/" + chunkId + "]";
    }
}
