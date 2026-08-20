package org.example.wavepilot.knowledge.repository;

import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;

import java.util.List;

public interface WavePilotKnowledgeRepository {
    void upsertDocument(List<KnowledgeChunk> chunks);
    List<KnowledgeSearchResult> search(KnowledgeSearchRequest request);
    String storageDescription();
}
