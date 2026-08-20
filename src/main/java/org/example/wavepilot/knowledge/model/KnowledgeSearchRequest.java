package org.example.wavepilot.knowledge.model;

import org.example.wavepilot.experiment.model.ExperimentType;

public record KnowledgeSearchRequest(
        String query,
        Integer topK,
        DocumentType documentType,
        ExperimentType experimentType) {

    public int normalizedTopK() {
        return topK == null ? 5 : Math.max(1, Math.min(20, topK));
    }
}
