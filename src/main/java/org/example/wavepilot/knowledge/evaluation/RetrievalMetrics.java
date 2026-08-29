package org.example.wavepilot.knowledge.evaluation;

public record RetrievalMetrics(
        double recallAt1,
        double recallAt3,
        double recallAt5,
        double precisionAt3,
        double precisionAt5,
        double mrr,
        double ndcgAt3,
        double ndcgAt5,
        double citationHitRate,
        double hardNegativeRejectionRate,
        double averageLatencyMillis,
        double averageRerankLatencyMillis,
        int caseCount) {
    public double recallAtK() { return recallAt5; }
    public double precisionAtK() { return precisionAt5; }
    public double ndcgAtK() { return ndcgAt5; }
}
