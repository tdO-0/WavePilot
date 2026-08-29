package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.knowledge.retrieval.QueryType;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;

import java.util.List;

public record RetrievalCaseResult(
        String caseId,
        RetrievalStrategy strategy,
        QueryType expectedQueryType,
        QueryType actualQueryType,
        List<String> retrievedChunkIds,
        double recallAt1,
        double recallAt3,
        double recallAt5,
        double precisionAt3,
        double precisionAt5,
        double reciprocalRank,
        double ndcgAt3,
        double ndcgAt5,
        double citationHitRate,
        double hardNegativeRejectionRate,
        long totalLatencyMillis,
        long rerankLatencyMillis,
        String rerankerUsed) {
    public RetrievalCaseResult {
        retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
        rerankerUsed = rerankerUsed == null ? "none" : rerankerUsed;
    }

    public double recallAtK() { return recallAt5; }
    public double precisionAtK() { return precisionAt5; }
    public double ndcgAtK() { return ndcgAt5; }
}
