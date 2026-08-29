package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository;
import org.example.wavepilot.knowledge.retrieval.HybridRetrievalService;
import org.example.wavepilot.knowledge.retrieval.RetrievalResponse;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.example.wavepilot.knowledge.retrieval.SparseRetriever;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RetrievalEvaluationService {
    private static final List<RetrievalStrategy> STRATEGIES = List.of(
            RetrievalStrategy.DENSE_ONLY, RetrievalStrategy.BM25_ONLY,
            RetrievalStrategy.HYBRID_RRF, RetrievalStrategy.HYBRID_RRF_DETERMINISTIC_RERANK,
            RetrievalStrategy.HYBRID_RRF_MODEL_RERANK);

    private final RetrievalEvaluationDataset dataset;
    private final WavePilotKnowledgeRepository denseStore;
    private final SparseRetriever sparseRetriever;
    private final HybridRetrievalService retrievalService;
    private final RetrievalMetricCalculator calculator;
    private final ArtifactRegistry artifactRegistry;
    private final ConcurrentMap<String, RetrievalEvaluationReport> reports = new ConcurrentHashMap<>();

    public RetrievalEvaluationService(RetrievalEvaluationDataset dataset,
                                      WavePilotKnowledgeRepository denseStore,
                                      SparseRetriever sparseRetriever,
                                      HybridRetrievalService retrievalService,
                                      RetrievalMetricCalculator calculator,
                                      ArtifactRegistry artifactRegistry) {
        this.dataset = dataset;
        this.denseStore = denseStore;
        this.sparseRetriever = sparseRetriever;
        this.retrievalService = retrievalService;
        this.calculator = calculator;
        this.artifactRegistry = artifactRegistry;
    }

    public RetrievalEvaluationReport run() {
        seedCorpus();
        List<RetrievalCaseResult> allResults = new ArrayList<>();
        Map<RetrievalStrategy, RetrievalMetrics> metrics = new EnumMap<>(RetrievalStrategy.class);
        for (RetrievalStrategy strategy : STRATEGIES) {
            List<RetrievalCaseResult> strategyResults = new ArrayList<>();
            for (RetrievalEvaluationCase evalCase : dataset.cases()) {
                KnowledgeSearchRequest request = new KnowledgeSearchRequest(evalCase.query(), evalCase.topK(),
                        evalCase.documentTypeFilter(), evalCase.experimentTypeFilter());
                RetrievalResponse response = retrievalService.search(request, strategy);
                strategyResults.add(calculator.calculate(evalCase, strategy,
                        response.route().queryType(), response));
            }
            allResults.addAll(strategyResults);
            metrics.put(strategy, calculator.aggregate(strategyResults));
        }
        String evaluationId = "RAGEVAL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Map<org.example.wavepilot.knowledge.retrieval.QueryType, Integer> typeCounts =
                new EnumMap<>(org.example.wavepilot.knowledge.retrieval.QueryType.class);
        dataset.cases().forEach(value -> typeCounts.merge(value.queryType(), 1, Integer::sum));
        List<RetrievalEvaluationComparison> comparisons = comparisons(metrics);
        RetrievalEvaluationReport report = new RetrievalEvaluationReport(evaluationId, dataset.name(),
                dataset.cases().size(), 5, Instant.now(), metrics, allResults, typeCounts, comparisons,
                "Bilingual offline software retrieval evaluation. The deterministic embedding and model-rerank fallback are not a scientific semantic benchmark.");
        reports.put(evaluationId, report);
        writeArtifacts(report);
        return report;
    }

    public RetrievalEvaluationReport get(String evaluationId) {
        RetrievalEvaluationReport report = reports.get(evaluationId);
        if (report == null) throw new NoSuchElementException("Retrieval evaluation not found: " + evaluationId);
        return report;
    }

    public String markdown(String evaluationId) { return renderMarkdown(get(evaluationId)); }

    public String renderMarkdown(RetrievalEvaluationReport report) {
        StringBuilder markdown = new StringBuilder("# Retrieval Evaluation\n\n")
                .append("- Evaluation: `").append(report.evaluationId()).append("`\n")
                .append("- Dataset: `").append(report.datasetName()).append("`\n")
                .append("- Cases: ").append(report.caseCount()).append("\n")
                .append("- Query types: ").append(report.queryTypeCounts()).append("\n")
                .append("- Disclosure: ").append(report.disclosure()).append("\n\n")
                .append("| Strategy | R@1 | R@3 | R@5 | P@3 | P@5 | MRR | nDCG@3 | nDCG@5 | Citation | Hard-neg reject | Avg ms | Rerank ms |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (RetrievalStrategy strategy : STRATEGIES) {
            RetrievalMetrics value = report.metrics().get(strategy);
            markdown.append("| ").append(strategy).append(" | ")
                    .append(format(value.recallAt1())).append(" | ")
                    .append(format(value.recallAt3())).append(" | ")
                    .append(format(value.recallAt5())).append(" | ")
                    .append(format(value.precisionAt3())).append(" | ")
                    .append(format(value.precisionAt5())).append(" | ")
                    .append(format(value.mrr())).append(" | ")
                    .append(format(value.ndcgAt3())).append(" | ")
                    .append(format(value.ndcgAt5())).append(" | ")
                    .append(format(value.citationHitRate())).append(" | ")
                    .append(format(value.hardNegativeRejectionRate())).append(" | ")
                    .append(format(value.averageLatencyMillis())).append(" | ")
                    .append(format(value.averageRerankLatencyMillis())).append(" |\n");
        }
        markdown.append("\n## Baseline vs candidate\n\n");
        report.comparisons().forEach(comparison -> markdown.append("- ")
                .append(comparison.baseline()).append(" → ").append(comparison.candidate())
                .append(": ").append(comparison.metricDeltas()).append(". ")
                .append(comparison.interpretation()).append('\n'));
        return markdown.toString();
    }

    private void seedCorpus() {
        Map<String, List<KnowledgeChunk>> byDocument = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : dataset.chunks()) {
            byDocument.computeIfAbsent(chunk.metadata().documentId(), ignored -> new ArrayList<>()).add(chunk);
        }
        for (List<KnowledgeChunk> chunks : byDocument.values()) {
            denseStore.upsertDocument(chunks);
            sparseRetriever.upsertDocument(chunks);
        }
    }

    private void writeArtifacts(RetrievalEvaluationReport report) {
        try {
            artifactRegistry.writeJson(report.evaluationId(), ArtifactType.RETRIEVAL_EVAL_JSON,
                    "retrieval-eval.json", report);
            artifactRegistry.writeJson(report.evaluationId(), ArtifactType.RETRIEVAL_EVAL_COMPARISON,
                    "retrieval-eval-comparison.json", report.comparisons());
            Path markdown = artifactRegistry.createJobDirectory(report.evaluationId())
                    .resolve("retrieval-eval.md");
            Files.writeString(markdown, renderMarkdown(report), StandardCharsets.UTF_8);
            artifactRegistry.register(report.evaluationId(), ArtifactType.RETRIEVAL_EVAL_MARKDOWN, markdown);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write retrieval evaluation artifacts", e);
        }
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private List<RetrievalEvaluationComparison> comparisons(
            Map<RetrievalStrategy, RetrievalMetrics> metrics) {
        return List.of(compare(metrics, RetrievalStrategy.DENSE_ONLY, RetrievalStrategy.HYBRID_RRF),
                compare(metrics, RetrievalStrategy.HYBRID_RRF,
                        RetrievalStrategy.HYBRID_RRF_DETERMINISTIC_RERANK),
                compare(metrics, RetrievalStrategy.HYBRID_RRF_DETERMINISTIC_RERANK,
                        RetrievalStrategy.HYBRID_RRF_MODEL_RERANK));
    }

    private RetrievalEvaluationComparison compare(Map<RetrievalStrategy, RetrievalMetrics> metrics,
                                                   RetrievalStrategy baseline,
                                                   RetrievalStrategy candidate) {
        RetrievalMetrics before = metrics.get(baseline);
        RetrievalMetrics after = metrics.get(candidate);
        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put("recallAt1", after.recallAt1() - before.recallAt1());
        deltas.put("recallAt3", after.recallAt3() - before.recallAt3());
        deltas.put("recallAt5", after.recallAt5() - before.recallAt5());
        deltas.put("precisionAt3", after.precisionAt3() - before.precisionAt3());
        deltas.put("precisionAt5", after.precisionAt5() - before.precisionAt5());
        deltas.put("mrr", after.mrr() - before.mrr());
        deltas.put("ndcgAt3", after.ndcgAt3() - before.ndcgAt3());
        deltas.put("ndcgAt5", after.ndcgAt5() - before.ndcgAt5());
        deltas.put("citationHitRate", after.citationHitRate() - before.citationHitRate());
        deltas.put("hardNegativeRejectionRate",
                after.hardNegativeRejectionRate() - before.hardNegativeRejectionRate());
        deltas.put("averageLatencyMillis",
                after.averageLatencyMillis() - before.averageLatencyMillis());
        deltas.put("averageRerankLatencyMillis",
                after.averageRerankLatencyMillis() - before.averageRerankLatencyMillis());
        boolean improved = deltas.entrySet().stream()
                .filter(entry -> !entry.getKey().contains("Latency"))
                .anyMatch(entry -> entry.getValue() > 1.0e-12);
        return new RetrievalEvaluationComparison(baseline, candidate, deltas, improved,
                improved ? "At least one measured metric improved; inspect negative deltas and make no overall or causal claim."
                        : "No measured quality improvement on this dataset; retain as an engineering comparison.");
    }
}
