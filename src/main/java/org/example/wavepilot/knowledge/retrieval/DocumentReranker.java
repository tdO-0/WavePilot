package org.example.wavepilot.knowledge.retrieval;

import java.util.List;

public interface DocumentReranker {
    String name();
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates);
    /** Actual implementation used for the most recent call (for fallback observability). */
    default String lastMode() { return name(); }
}
