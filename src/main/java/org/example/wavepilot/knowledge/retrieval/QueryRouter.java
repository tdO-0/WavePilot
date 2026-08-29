package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Deterministic routing controls filters and retrieval policy; it is not a prompt-per-agent layer. */
@Component
public class QueryRouter {
    private static final Set<String> TROUBLE = Set.of("error", "exception", "failed", "failure",
            "debug", "错误", "异常", "失败", "排查", "修复");
    private static final Set<String> PARAMETER = Set.of("parameter", "range", "step", "sample",
            "monte", "参数", "范围", "步长", "样本", "次数", "阈值");
    private static final Set<String> GUIDANCE = Set.of("experiment", "recipe", "run", "workflow",
            "实验", "方案", "流程", "如何执行", "指导");

    private final HybridRetrievalProperties properties;

    public QueryRouter(HybridRetrievalProperties properties) {
        this.properties = properties;
    }

    public QueryRoute route(KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("Knowledge query is required");
        }
        String normalized = request.query().toLowerCase(Locale.ROOT);
        QueryType type;
        DocumentType inferred;
        if (contains(normalized, TROUBLE)) {
            type = QueryType.TROUBLESHOOTING;
            inferred = DocumentType.FAILURE_CASE;
        } else if (contains(normalized, PARAMETER)) {
            type = QueryType.PARAMETER;
            inferred = DocumentType.MATLAB_GUIDE;
        } else if (contains(normalized, GUIDANCE)) {
            type = QueryType.EXPERIMENT_GUIDANCE;
            inferred = DocumentType.EXPERIMENT_RECIPE;
        } else {
            type = QueryType.THEORY;
            inferred = DocumentType.THEORY;
        }
        DocumentType filter = request.documentType() == null ? inferred : request.documentType();
        int requestedTopK = request.topK() == null ? properties.getResultTopK() : request.normalizedTopK();
        return new QueryRoute(type, filter, request.experimentType(),
                RetrievalStrategy.HYBRID_RRF_RERANK,
                Math.max(properties.getDenseCandidateK(), requestedTopK),
                Math.max(properties.getSparseCandidateK(), requestedTopK),
                requestedTopK, true,
                "deterministic query intent controls metadata filter, candidate counts and rerank");
    }

    private boolean contains(String query, Set<String> terms) {
        return terms.stream().anyMatch(query::contains);
    }
}
