package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.example.wavepilot.knowledge.retrieval.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RetrievalEvaluationReport(
        String evaluationId,
        String datasetName,
        int caseCount,
        int topK,
        Instant generatedAt,
        Map<RetrievalStrategy, RetrievalMetrics> metrics,
        List<RetrievalCaseResult> caseResults,
        Map<QueryType, Integer> queryTypeCounts,
        List<RetrievalEvaluationComparison> comparisons,
        String disclosure) {
    public RetrievalEvaluationReport {
        metrics = Map.copyOf(metrics);
        caseResults = List.copyOf(caseResults);
        queryTypeCounts = Map.copyOf(queryTypeCounts);
        comparisons = List.copyOf(comparisons);
    }
}
