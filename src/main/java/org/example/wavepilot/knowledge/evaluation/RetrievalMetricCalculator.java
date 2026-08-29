package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.retrieval.QueryType;
import org.example.wavepilot.knowledge.retrieval.RetrievalResponse;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.ToDoubleFunction;

@Component
public class RetrievalMetricCalculator {
    public RetrievalCaseResult calculate(RetrievalEvaluationCase evalCase, RetrievalStrategy strategy,
                                         QueryType actualType, List<KnowledgeSearchResult> retrieved) {
        return calculate(evalCase, strategy, actualType, retrieved, 0, 0, "none");
    }

    public RetrievalCaseResult calculate(RetrievalEvaluationCase evalCase, RetrievalStrategy strategy,
                                         QueryType actualType, RetrievalResponse response) {
        long total = response.denseLatencyMillis() + response.sparseLatencyMillis()
                + response.fusionLatencyMillis() + response.rerankLatencyMillis();
        return calculate(evalCase, strategy, actualType, response.evidence(), total,
                response.rerankLatencyMillis(), response.rerankerUsed());
    }

    private RetrievalCaseResult calculate(RetrievalEvaluationCase evalCase, RetrievalStrategy strategy,
                                          QueryType actualType, List<KnowledgeSearchResult> retrieved,
                                          long totalLatency, long rerankLatency, String rerankerUsed) {
        List<KnowledgeSearchResult> top = retrieved.stream().limit(5).toList();
        int firstRank = 0;
        int citedRelevant = 0;
        int relevantAt5 = 0;
        for (int index = 0; index < top.size(); index++) {
            KnowledgeSearchResult result = top.get(index);
            if (isRelevant(evalCase, result)) {
                relevantAt5++;
                if (firstRank == 0) firstRank = index + 1;
                if (correctCitation(result)) citedRelevant++;
            }
        }
        int expected = Math.max(1, evalCase.relevantChunkIds().size() + evalCase.relevantDocumentIds().size());
        double hardRejected = evalCase.hardNegativeChunkIds().isEmpty() ? 1.0
                : (double) evalCase.hardNegativeChunkIds().stream()
                .filter(id -> top.stream().noneMatch(result -> result.chunkId().equals(id))).count()
                / evalCase.hardNegativeChunkIds().size();
        return new RetrievalCaseResult(evalCase.caseId(), strategy, evalCase.queryType(), actualType,
                top.stream().map(KnowledgeSearchResult::chunkId).toList(),
                recall(evalCase, top, 1, expected), recall(evalCase, top, 3, expected),
                recall(evalCase, top, 5, expected), precision(evalCase, top, 3),
                precision(evalCase, top, 5), firstRank == 0 ? 0 : 1.0 / firstRank,
                ndcg(evalCase, top, 3, expected), ndcg(evalCase, top, 5, expected),
                relevantAt5 == 0 ? 0 : (double) citedRelevant / relevantAt5,
                hardRejected, totalLatency, rerankLatency, rerankerUsed);
    }

    public RetrievalMetrics aggregate(List<RetrievalCaseResult> results) {
        if (results.isEmpty()) return new RetrievalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        return new RetrievalMetrics(avg(results, RetrievalCaseResult::recallAt1),
                avg(results, RetrievalCaseResult::recallAt3), avg(results, RetrievalCaseResult::recallAt5),
                avg(results, RetrievalCaseResult::precisionAt3), avg(results, RetrievalCaseResult::precisionAt5),
                avg(results, RetrievalCaseResult::reciprocalRank), avg(results, RetrievalCaseResult::ndcgAt3),
                avg(results, RetrievalCaseResult::ndcgAt5), avg(results, RetrievalCaseResult::citationHitRate),
                avg(results, RetrievalCaseResult::hardNegativeRejectionRate),
                avg(results, value -> value.totalLatencyMillis()),
                avg(results, value -> value.rerankLatencyMillis()), results.size());
    }

    private double recall(RetrievalEvaluationCase evalCase, List<KnowledgeSearchResult> top,
                          int k, int expected) {
        return (double) relevant(evalCase, top, k) / expected;
    }

    private double precision(RetrievalEvaluationCase evalCase, List<KnowledgeSearchResult> top, int k) {
        return (double) relevant(evalCase, top, k) / k;
    }

    private int relevant(RetrievalEvaluationCase evalCase, List<KnowledgeSearchResult> top, int k) {
        return (int) top.stream().limit(k).filter(result -> isRelevant(evalCase, result)).count();
    }

    private double ndcg(RetrievalEvaluationCase evalCase, List<KnowledgeSearchResult> top,
                        int k, int expected) {
        double dcg = 0;
        for (int index = 0; index < Math.min(k, top.size()); index++) {
            if (isRelevant(evalCase, top.get(index))) dcg += 1.0 / log2(index + 2.0);
        }
        double ideal = 0;
        for (int index = 0; index < Math.min(expected, k); index++) ideal += 1.0 / log2(index + 2.0);
        return ideal == 0 ? 0 : dcg / ideal;
    }

    private boolean isRelevant(RetrievalEvaluationCase evalCase, KnowledgeSearchResult result) {
        return evalCase.relevantChunkIds().contains(result.chunkId())
                || evalCase.relevantDocumentIds().contains(result.documentId());
    }

    private boolean correctCitation(KnowledgeSearchResult result) {
        return result.source() != null && !result.source().isBlank()
                && ("KB[" + result.documentId() + "/" + result.chunkId() + "]").equals(result.citation());
    }

    private double avg(List<RetrievalCaseResult> results,
                       ToDoubleFunction<RetrievalCaseResult> metric) {
        return results.stream().mapToDouble(metric).average().orElse(0);
    }

    private double log2(double value) { return Math.log(value) / Math.log(2); }
}
