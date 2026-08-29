package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic routing controls filters and retrieval policy; it is not a prompt-per-agent layer. */
@Component
public class QueryRouter {
    private static final Set<String> TROUBLE = Set.of("error", "exception", "failed", "failure",
            "debug", "undefined", "mismatch", "nan", "inf", "overflow", "错误", "异常", "失败",
            "报错", "排查", "修复", "未定义", "维度不一致", "索引越界");
    private static final Set<String> PARAMETER = Set.of("parameter", "range", "step", "sample",
            "monte", "seed", "snr", "eb/n0", "ebn0", "frame", "iteration", "参数", "范围",
            "步长", "样本", "次数", "阈值", "随机种子", "码长", "信噪比");
    private static final Set<String> GUIDANCE = Set.of("experiment", "recipe", "run", "workflow",
            "procedure", "benchmark", "compare", "reproduce", "实验", "方案", "流程", "如何执行",
            "如何验证", "复现", "对比", "指导");

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
        DocumentType hardFilter = request.documentType();
        List<DocumentType> fallbacks = Arrays.stream(DocumentType.values())
                .filter(value -> value != inferred).toList();
        int requestedTopK = request.topK() == null ? properties.getResultTopK() : request.normalizedTopK();
        return new QueryRoute(type, hardFilter, inferred, fallbacks, properties.getRoutingBoost(),
                request.experimentType(), RetrievalStrategy.HYBRID_RRF_RERANK,
                Math.max(properties.getDenseCandidateK(), requestedTopK),
                Math.max(properties.getSparseCandidateK(), requestedTopK),
                requestedTopK, true,
                hardFilter == null
                        ? "deterministic query intent is a soft document-type boost; no inferred hard filter"
                        : "explicit user document type is a hard filter; router intent remains a ranking hint");
    }

    private boolean contains(String query, Set<String> terms) {
        return terms.stream().anyMatch(query::contains);
    }
}
