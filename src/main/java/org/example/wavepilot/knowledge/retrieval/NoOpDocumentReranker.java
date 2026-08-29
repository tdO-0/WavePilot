package org.example.wavepilot.knowledge.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NoOpDocumentReranker implements DocumentReranker {
    @Override public String name() { return "noop"; }
    @Override public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        return List.copyOf(candidates);
    }
}
