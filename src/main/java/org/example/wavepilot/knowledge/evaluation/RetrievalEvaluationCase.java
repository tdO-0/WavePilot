package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.retrieval.QueryType;

import java.util.Set;

public record RetrievalEvaluationCase(
        String caseId,
        String query,
        Set<String> relevantChunkIds,
        Set<String> relevantDocumentIds,
        QueryType queryType,
        DocumentType documentTypeFilter,
        ExperimentType experimentTypeFilter,
        int topK) {
    public RetrievalEvaluationCase {
        relevantChunkIds = relevantChunkIds == null ? Set.of() : Set.copyOf(relevantChunkIds);
        relevantDocumentIds = relevantDocumentIds == null ? Set.of() : Set.copyOf(relevantDocumentIds);
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        if (relevantChunkIds.isEmpty() && relevantDocumentIds.isEmpty()) {
            throw new IllegalArgumentException("at least one relevant chunk/document id is required");
        }
        if (queryType == null || topK < 1) throw new IllegalArgumentException("queryType/topK is invalid");
    }
}
