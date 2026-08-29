package org.example.wavepilot.knowledge.retrieval;

import java.util.List;

/** Optional model adapter. No implementation is enabled in offline mode. */
public class ModelBasedDocumentReranker implements DocumentReranker {
    public interface ScoringModel {
        List<String> rankChunkIds(String query, List<RetrievalCandidate> candidates);
    }

    private final ScoringModel model;

    public ModelBasedDocumentReranker(ScoringModel model) { this.model = model; }
    @Override public String name() { return "model"; }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        List<String> order = model.rankChunkIds(query, List.copyOf(candidates));
        java.util.Map<String, Integer> rank = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.putIfAbsent(order.get(i), i);
        return candidates.stream().sorted(java.util.Comparator
                        .comparingInt((RetrievalCandidate candidate) ->
                                rank.getOrDefault(candidate.evidence().chunkId(), Integer.MAX_VALUE))
                        .thenComparing(java.util.Comparator.comparingDouble(RetrievalCandidate::rawScore).reversed()))
                .toList();
    }
}
