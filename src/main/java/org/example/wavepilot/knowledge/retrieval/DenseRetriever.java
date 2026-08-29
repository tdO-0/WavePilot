package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;

import java.util.List;

public interface DenseRetriever {
    List<RetrievalCandidate> search(KnowledgeSearchRequest request, int candidateK);
}
