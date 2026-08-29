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
        double recallAtK,
        double precisionAtK,
        double reciprocalRank,
        double ndcgAtK,
        double citationHitRate) {
    public RetrievalCaseResult {
        retrievedChunkIds = List.copyOf(retrievedChunkIds);
    }
}
