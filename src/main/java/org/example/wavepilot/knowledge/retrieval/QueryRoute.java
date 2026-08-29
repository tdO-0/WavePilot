package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;

public record QueryRoute(
        QueryType queryType,
        DocumentType documentType,
        ExperimentType experimentType,
        RetrievalStrategy strategy,
        int denseCandidateK,
        int sparseCandidateK,
        int topK,
        boolean rerank,
        String reason) {
}
