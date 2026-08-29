package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.retrieval.QueryType;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RetrievalMetricCalculator {
    public RetrievalCaseResult calculate(RetrievalEvaluationCase evalCase, RetrievalStrategy strategy,
                                         QueryType actualType, List<KnowledgeSearchResult> retrieved) {
        List<KnowledgeSearchResult> top = retrieved.stream().limit(evalCase.topK()).toList();
        int relevant = 0;
        int firstRank = 0;
        double dcg = 0;
        int citedRelevant = 0;
        for (int index = 0; index < top.size(); index++) {
            KnowledgeSearchResult result = top.get(index);
            if (isRelevant(evalCase, result)) {
                relevant++;
                if (firstRank == 0) firstRank = index + 1;
                dcg += 1.0 / log2(index + 2.0);
                if (correctCitation(result)) citedRelevant++;
            }
        }
        int expected = Math.max(1, evalCase.relevantChunkIds().size() + evalCase.relevantDocumentIds().size());
        double idcg = 0;
        for (int index = 0; index < Math.min(expected, evalCase.topK()); index++) {
            idcg += 1.0 / log2(index + 2.0);
        }
        return new RetrievalCaseResult(evalCase.caseId(), strategy, evalCase.queryType(), actualType,
                top.stream().map(KnowledgeSearchResult::chunkId).toList(),
                (double) relevant / expected,
                (double) relevant / evalCase.topK(),
                firstRank == 0 ? 0 : 1.0 / firstRank,
                idcg == 0 ? 0 : dcg / idcg,
                relevant == 0 ? 0 : (double) citedRelevant / relevant);
    }

    public RetrievalMetrics aggregate(List<RetrievalCaseResult> results) {
        if (results.isEmpty()) return new RetrievalMetrics(0, 0, 0, 0, 0, 0);
        return new RetrievalMetrics(average(results, Metric.RECALL), average(results, Metric.PRECISION),
                average(results, Metric.MRR), average(results, Metric.NDCG),
                average(results, Metric.CITATION), results.size());
    }

    private boolean isRelevant(RetrievalEvaluationCase evalCase, KnowledgeSearchResult result) {
        return evalCase.relevantChunkIds().contains(result.chunkId())
                || evalCase.relevantDocumentIds().contains(result.documentId());
    }

    private boolean correctCitation(KnowledgeSearchResult result) {
        return result.source() != null && !result.source().isBlank()
                && ("KB[" + result.documentId() + "/" + result.chunkId() + "]").equals(result.citation());
    }

    private double average(List<RetrievalCaseResult> results, Metric metric) {
        return results.stream().mapToDouble(result -> switch (metric) {
            case RECALL -> result.recallAtK();
            case PRECISION -> result.precisionAtK();
            case MRR -> result.reciprocalRank();
            case NDCG -> result.ndcgAtK();
            case CITATION -> result.citationHitRate();
        }).average().orElse(0);
    }

    private double log2(double value) { return Math.log(value) / Math.log(2); }
    private enum Metric { RECALL, PRECISION, MRR, NDCG, CITATION }
}
