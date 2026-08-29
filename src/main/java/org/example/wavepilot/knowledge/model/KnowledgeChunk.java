package org.example.wavepilot.knowledge.model;

import java.util.Map;

public record KnowledgeChunk(
        String chunkId,
        KnowledgeDocumentMetadata metadata,
        String content,
        String section,
        Map<String, String> attributes) {

    /** Compatibility constructor for the original dense-only model. */
    public KnowledgeChunk(String chunkId, KnowledgeDocumentMetadata metadata, String content) {
        this(chunkId, metadata, content, "", Map.of());
    }

    public KnowledgeChunk {
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("chunkId is required");
        if (metadata == null) throw new IllegalArgumentException("metadata is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        section = section == null ? "" : section.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String citation() {
        return "KB[" + metadata.documentId() + "/" + chunkId + "]";
    }
}
