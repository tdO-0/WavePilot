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
        QueryRoute route = new QueryRoute(routed.queryType(), routed.documentType(),
                routed.primaryDocumentType(), routed.fallbackDocumentTypes(), routed.primaryDocumentBoost(),
                routed.experimentType(), strategy, routed.denseCandidateK(), routed.sparseCandidateK(),
                routed.topK(), usesReranker(strategy), routed.reason());
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
            case DENSE_ONLY -> normalized(dense, "DENSE", route);
            case BM25_ONLY -> normalized(sparse, "BM25", route);
            case HYBRID_RRF, HYBRID_RRF_RERANK, HYBRID_RRF_DETERMINISTIC_RERANK,
                    HYBRID_RRF_MODEL_RERANK -> reciprocalRankFusion(dense, sparse, route);
        };
        long fusionMillis = elapsedMillis(fusionStarted);

        long rerankMillis = 0;
        String rerankerUsed = "none";
        if (usesReranker(strategy)) {
            DocumentReranker reranker = selectedReranker(strategy);
            long started = System.nanoTime();
            ranked = reranker.rerank(original.query(), ranked);
            rerankMillis = elapsedMillis(started);
            rerankerUsed = reranker.lastMode();
            String method = "HYBRID_RRF+" + rerankerUsed.toUpperCase(java.util.Locale.ROOT);
            ranked = ranked.stream().map(candidate -> new RetrievalCandidate(
                    candidate.evidence().withScoreAndMethod(candidate.rawScore(), method),
                    candidate.rawScore())).toList();
        }
        List<KnowledgeSearchResult> evidence = ranked.stream().limit(route.topK())
                .map(RetrievalCandidate::evidence).toList();
        return new RetrievalResponse(route, evidence, dense.size(), sparse.size(), denseMillis,
                sparseMillis, fusionMillis, rerankMillis, rerankerUsed);
    }

    private List<RetrievalCandidate> normalized(List<RetrievalCandidate> candidates, String method,
                                                QueryRoute route) {
        return candidates.stream().map(candidate -> new RetrievalCandidate(
                        candidate.evidence().withScoreAndMethod(boosted(candidate, route), method),
                        boosted(candidate, route)))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rawScore).reversed()
                        .thenComparing(candidate -> candidate.evidence().chunkId()))
                .toList();
    }

    /** RRF combines ranks only; dense similarity and BM25 scores are never linearly mixed. */
    private List<RetrievalCandidate> reciprocalRankFusion(List<RetrievalCandidate> dense,
                                                          List<RetrievalCandidate> sparse,
                                                          QueryRoute route) {
        Map<String, Fused> fused = new LinkedHashMap<>();
        addRanks(fused, dense, route);
        addRanks(fused, sparse, route);
        return fused.values().stream()
                .map(value -> new RetrievalCandidate(
                        value.evidence.withScoreAndMethod(value.score, "HYBRID_RRF"), value.score))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rawScore).reversed()
                        .thenComparing(candidate -> candidate.evidence().chunkId()))
                .toList();
    }

    private void addRanks(Map<String, Fused> fused, List<RetrievalCandidate> ranked, QueryRoute route) {
        for (int index = 0; index < ranked.size(); index++) {
            RetrievalCandidate candidate = ranked.get(index);
            double contribution = 1.0 / (properties.getRrfK() + index + 1.0);
            if (!route.explicitDocumentFilter()
                    && candidate.evidence().documentType() == route.primaryDocumentType()) {
                contribution *= route.primaryDocumentBoost();
            }
            double rankContribution = contribution;
            fused.compute(candidate.evidence().chunkId(), (id, existing) -> existing == null
                    ? new Fused(candidate.evidence(), rankContribution)
                    : new Fused(existing.evidence, existing.score + rankContribution));
        }
    }

    private DocumentReranker selectedReranker(RetrievalStrategy strategy) {
        String selected = switch (strategy) {
            case HYBRID_RRF_DETERMINISTIC_RERANK -> "deterministic";
            case HYBRID_RRF_MODEL_RERANK -> "model";
            default -> properties.getReranker();
        };
        return rerankers.stream().filter(reranker -> reranker.name().equalsIgnoreCase(selected))
                .findFirst().orElseGet(() -> rerankers.stream()
                        .filter(reranker -> reranker.name().equals("deterministic")).findFirst()
                        .or(() -> rerankers.stream().filter(reranker -> reranker.name().equals("noop")).findFirst())
                        .orElseThrow(() -> new IllegalStateException("No document reranker is configured")));
    }

    private boolean usesReranker(RetrievalStrategy strategy) {
        return strategy == RetrievalStrategy.HYBRID_RRF_RERANK
                || strategy == RetrievalStrategy.HYBRID_RRF_DETERMINISTIC_RERANK
                || strategy == RetrievalStrategy.HYBRID_RRF_MODEL_RERANK;
    }

    private double boosted(RetrievalCandidate candidate, QueryRoute route) {
        return !route.explicitDocumentFilter()
                && candidate.evidence().documentType() == route.primaryDocumentType()
                ? candidate.rawScore() * route.primaryDocumentBoost()
                : candidate.rawScore();
    }

    private long elapsedMillis(long startedNanos) {
        long nanos = System.nanoTime() - startedNanos;
        return nanos == 0 ? 0 : Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private record Fused(KnowledgeSearchResult evidence, double score) { }
}
