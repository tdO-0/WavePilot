package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;

import java.util.List;

public record QueryRoute(
        QueryType queryType,
        /** Explicit user filter only. A null value means no hard document-type filter. */
        DocumentType documentType,
        /** Router inference used only as a ranking hint. */
        DocumentType primaryDocumentType,
        List<DocumentType> fallbackDocumentTypes,
        double primaryDocumentBoost,
        ExperimentType experimentType,
        RetrievalStrategy strategy,
        int denseCandidateK,
        int sparseCandidateK,
        int topK,
        boolean rerank,
        String reason) {
    public QueryRoute {
        fallbackDocumentTypes = fallbackDocumentTypes == null ? List.of() : List.copyOf(fallbackDocumentTypes);
        primaryDocumentBoost = Math.max(1.0, Math.min(2.0, primaryDocumentBoost));
    }

    public boolean explicitDocumentFilter() { return documentType != null; }
}
