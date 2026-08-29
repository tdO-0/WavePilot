package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;

import java.util.List;

public interface SparseRetriever {
    void upsertDocument(List<KnowledgeChunk> chunks);
    List<RetrievalCandidate> search(KnowledgeSearchRequest request, int candidateK);
}
