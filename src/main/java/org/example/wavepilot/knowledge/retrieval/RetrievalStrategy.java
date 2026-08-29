package org.example.wavepilot.knowledge.retrieval;

public enum RetrievalStrategy {
    DENSE_ONLY,
    BM25_ONLY,
    HYBRID_RRF,
    /** Backward-compatible configured reranker. */
    HYBRID_RRF_RERANK,
    HYBRID_RRF_DETERMINISTIC_RERANK,
    HYBRID_RRF_MODEL_RERANK
}
