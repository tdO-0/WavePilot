package org.example.wavepilot.knowledge.retrieval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controlled listwise reranker. A model may only return a permutation of the supplied
 * chunk ids. Unknown, missing or duplicate ids and provider failures all fall back to
 * the deterministic offline reranker.
 */
@Component
public class ModelBasedDocumentReranker implements DocumentReranker {
    public interface ScoringModel {
        List<String> rankChunkIds(String query, List<RetrievalCandidate> candidates);
        default boolean available() { return true; }
    }

    private final ScoringModel model;
    private final DocumentReranker fallback;
    private final ThreadLocal<String> lastMode = ThreadLocal.withInitial(() -> "model-fallback-deterministic");

    @Autowired
    public ModelBasedDocumentReranker(List<ScoringModel> models,
                                      DeterministicDocumentReranker fallback) {
        this(models.stream().filter(ScoringModel::available).findFirst().orElse(null), fallback);
    }

    public ModelBasedDocumentReranker(ScoringModel model) {
        this(model, new DeterministicDocumentReranker());
    }

    public ModelBasedDocumentReranker(ScoringModel model, DocumentReranker fallback) {
        this.model = model;
        this.fallback = fallback;
    }

    @Override public String name() { return "model"; }

    @Override public String lastMode() { return lastMode.get(); }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        List<RetrievalCandidate> immutable = List.copyOf(candidates);
        if (immutable.size() < 2 || model == null || !model.available()) return fallback(query, immutable);
        try {
            List<String> order = model.rankChunkIds(query, immutable);
            if (!validPermutation(order, immutable)) return fallback(query, immutable);
            Map<String, RetrievalCandidate> byId = new HashMap<>();
            immutable.forEach(candidate -> byId.put(candidate.evidence().chunkId(), candidate));
            List<RetrievalCandidate> reranked = new ArrayList<>(order.size());
            order.forEach(id -> reranked.add(byId.get(id)));
            lastMode.set("model");
            return List.copyOf(reranked);
        } catch (RuntimeException providerOrParseFailure) {
            return fallback(query, immutable);
        }
    }

    private boolean validPermutation(List<String> order, List<RetrievalCandidate> candidates) {
        if (order == null || order.size() != candidates.size()) return false;
        Set<String> expected = new HashSet<>();
        candidates.forEach(candidate -> expected.add(candidate.evidence().chunkId()));
        return expected.size() == candidates.size()
                && new HashSet<>(order).size() == order.size()
                && expected.equals(new HashSet<>(order));
    }

    private List<RetrievalCandidate> fallback(String query, List<RetrievalCandidate> candidates) {
        lastMode.set("model-fallback-" + fallback.name());
        return fallback.rerank(query, candidates);
    }
}
