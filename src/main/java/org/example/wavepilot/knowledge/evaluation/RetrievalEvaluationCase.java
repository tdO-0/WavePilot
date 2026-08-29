package org.example.wavepilot.knowledge.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.retrieval.QueryType;

import java.util.Set;

/** One auditable retrieval judgment with optional explicit metadata filtering. */
public record RetrievalEvaluationCase(
        @JsonProperty("id") String caseId,
        String query,
        QueryType queryType,
        Set<String> relevantChunkIds,
        Set<String> relevantDocumentIds,
        Set<String> hardNegativeChunkIds,
        DocumentType documentType,
        ExperimentType experimentType,
        int topK,
        boolean explicitDocumentFilter) {

    public RetrievalEvaluationCase {
        relevantChunkIds = relevantChunkIds == null ? Set.of() : Set.copyOf(relevantChunkIds);
        relevantDocumentIds = relevantDocumentIds == null ? Set.of() : Set.copyOf(relevantDocumentIds);
        hardNegativeChunkIds = hardNegativeChunkIds == null ? Set.of() : Set.copyOf(hardNegativeChunkIds);
        if (caseId == null || caseId.isBlank()) throw new IllegalArgumentException("id is required");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        if (relevantChunkIds.isEmpty() && relevantDocumentIds.isEmpty()) {
            throw new IllegalArgumentException("at least one relevant chunk/document id is required");
        }
        if (queryType == null || documentType == null || experimentType == null || topK < 5) {
            throw new IllegalArgumentException("queryType/documentType/experimentType/topK is invalid");
        }
    }

    /** Compatibility constructor for the v1 six-case dataset/tests. */
    public RetrievalEvaluationCase(String caseId, String query, Set<String> relevantChunkIds,
                                   Set<String> relevantDocumentIds, QueryType queryType,
                                   DocumentType documentType, ExperimentType experimentType, int topK) {
        this(caseId, query, queryType, relevantChunkIds, relevantDocumentIds, Set.of(),
                documentType, experimentType, Math.max(5, topK), true);
    }

    public DocumentType documentTypeFilter() { return explicitDocumentFilter ? documentType : null; }
    public ExperimentType experimentTypeFilter() { return experimentType; }
}
