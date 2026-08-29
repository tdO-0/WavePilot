package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;

public record RetrievalCandidate(KnowledgeSearchResult evidence, double rawScore) {
    public RetrievalCandidate {
        if (evidence == null) throw new IllegalArgumentException("evidence is required");
    }
}
