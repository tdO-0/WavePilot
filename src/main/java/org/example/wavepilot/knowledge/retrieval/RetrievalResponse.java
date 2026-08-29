package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;

import java.util.List;

public record RetrievalResponse(
        QueryRoute route,
        List<KnowledgeSearchResult> evidence,
        int denseCandidateCount,
        int sparseCandidateCount,
        long denseLatencyMillis,
        long sparseLatencyMillis,
        long fusionLatencyMillis,
        long rerankLatencyMillis,
        String rerankerUsed) {
    public RetrievalResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        rerankerUsed = rerankerUsed == null ? "none" : rerankerUsed;
    }
}
