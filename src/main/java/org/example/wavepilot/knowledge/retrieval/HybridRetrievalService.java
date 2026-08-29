package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridRetrievalService {
    private final DenseRetriever denseRetriever;
    private final SparseRetriever sparseRetriever;
    private final QueryRouter queryRouter;
    private final HybridRetrievalProperties properties;
    private final List<DocumentReranker> rerankers;

    public HybridRetrievalService(DenseRetriever denseRetriever, SparseRetriever sparseRetriever,
                                  QueryRouter queryRouter, HybridRetrievalProperties properties,
                                  List<DocumentReranker> rerankers) {
        this.denseRetriever = denseRetriever;
        this.sparseRetriever = sparseRetriever;
        this.queryRouter = queryRouter;
        this.properties = properties;
        this.rerankers = List.copyOf(rerankers);
    }

    public RetrievalResponse search(KnowledgeSearchRequest request) {
        return search(request, queryRouter.route(request).strategy());
    }

    public RetrievalResponse search(KnowledgeSearchRequest original, RetrievalStrategy strategy) {
        QueryRoute routed = queryRouter.route(original);
        QueryRoute route = new QueryRoute(routed.queryType(), routed.documentType(), routed.experimentType(),
                strategy, routed.denseCandidateK(), routed.sparseCandidateK(), routed.topK(),
                strategy == RetrievalStrategy.HYBRID_RRF_RERANK, routed.reason());
        KnowledgeSearchRequest filtered = new KnowledgeSearchRequest(original.query(), null,
                route.documentType(), route.experimentType());

        List<RetrievalCandidate> dense = List.of();
        List<RetrievalCandidate> sparse = List.of();
        long denseMillis = 0;
        long sparseMillis = 0;
        if (strategy != RetrievalStrategy.BM25_ONLY) {
            long started = System.nanoTime();
            dense = denseRetriever.search(filtered, route.denseCandidateK());
            denseMillis = elapsedMillis(started);
        }
        if (strategy != RetrievalStrategy.DENSE_ONLY) {
            long started = System.nanoTime();
            sparse = sparseRetriever.search(filtered, route.sparseCandidateK());
            sparseMillis = elapsedMillis(started);
        }

        long fusionStarted = System.nanoTime();
        List<RetrievalCandidate> ranked = switch (strategy) {
            case DENSE_ONLY -> normalized(dense, "DENSE");
            case BM25_ONLY -> normalized(sparse, "BM25");
            case HYBRID_RRF, HYBRID_RRF_RERANK -> reciprocalRankFusion(dense, sparse);
        };
        long fusionMillis = elapsedMillis(fusionStarted);

        long rerankMillis = 0;
        if (strategy == RetrievalStrategy.HYBRID_RRF_RERANK) {
            long started = System.nanoTime();
            ranked = selectedReranker().rerank(original.query(), ranked);
            rerankMillis = elapsedMillis(started);
            String method = "HYBRID_RRF+" + selectedReranker().name().toUpperCase(java.util.Locale.ROOT);
            ranked = ranked.stream().map(candidate -> new RetrievalCandidate(
                    candidate.evidence().withScoreAndMethod(candidate.rawScore(), method),
                    candidate.rawScore())).toList();
        }
        List<KnowledgeSearchResult> evidence = ranked.stream().limit(route.topK())
                .map(RetrievalCandidate::evidence).toList();
        return new RetrievalResponse(route, evidence, dense.size(), sparse.size(), denseMillis,
                sparseMillis, fusionMillis, rerankMillis);
    }

    private List<RetrievalCandidate> normalized(List<RetrievalCandidate> candidates, String method) {
        return candidates.stream().map(candidate -> new RetrievalCandidate(
                        candidate.evidence().withScoreAndMethod(candidate.rawScore(), method),
                        candidate.rawScore()))
                .toList();
    }

    /** RRF combines ranks only; dense similarity and BM25 scores are never linearly mixed. */
    private List<RetrievalCandidate> reciprocalRankFusion(List<RetrievalCandidate> dense,
                                                          List<RetrievalCandidate> sparse) {
        Map<String, Fused> fused = new LinkedHashMap<>();
        addRanks(fused, dense);
        addRanks(fused, sparse);
        return fused.values().stream()
                .map(value -> new RetrievalCandidate(
                        value.evidence.withScoreAndMethod(value.score, "HYBRID_RRF"), value.score))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rawScore).reversed()
                        .thenComparing(candidate -> candidate.evidence().chunkId()))
                .toList();
    }

    private void addRanks(Map<String, Fused> fused, List<RetrievalCandidate> ranked) {
        for (int index = 0; index < ranked.size(); index++) {
            RetrievalCandidate candidate = ranked.get(index);
            double contribution = 1.0 / (properties.getRrfK() + index + 1.0);
            fused.compute(candidate.evidence().chunkId(), (id, existing) -> existing == null
                    ? new Fused(candidate.evidence(), contribution)
                    : new Fused(existing.evidence, existing.score + contribution));
        }
    }

    private DocumentReranker selectedReranker() {
        String selected = properties.getReranker();
        return rerankers.stream().filter(reranker -> reranker.name().equalsIgnoreCase(selected))
                .findFirst().orElseGet(() -> rerankers.stream()
                        .filter(reranker -> reranker.name().equals("noop")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No document reranker is configured")));
    }

    private long elapsedMillis(long startedNanos) {
        long nanos = System.nanoTime() - startedNanos;
        return nanos == 0 ? 0 : Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private record Fused(KnowledgeSearchResult evidence, double score) { }
}
