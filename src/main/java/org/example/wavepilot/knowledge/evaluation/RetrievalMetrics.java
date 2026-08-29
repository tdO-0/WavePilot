package org.example.wavepilot.knowledge.evaluation;

public record RetrievalMetrics(
        double recallAtK,
        double precisionAtK,
        double mrr,
        double ndcgAtK,
        double citationHitRate,
        int caseCount) {
}
