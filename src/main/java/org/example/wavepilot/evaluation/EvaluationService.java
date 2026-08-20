package org.example.wavepilot.evaluation;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.evaluation.executor.CitationGroundingExecutor;
import org.example.wavepilot.evaluation.executor.EvaluationCaseExecutor;
import org.example.wavepilot.evaluation.executor.JobCasesExecutor;
import org.example.wavepilot.evaluation.executor.KnowledgeRetrievalExecutor;
import org.example.wavepilot.evaluation.executor.ModelDrivenCaseExecutor;
import org.example.wavepilot.evaluation.executor.ReplayConsistencyExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Executes a fixed dataset with a named model, computes every metric from the actual per-case
 * results, registers EVAL_REPORT and EVAL_CASE_RESULTS artifacts, and compares baseline and
 * candidate runs case-by-case for the same dataset.
 */
@Service
public class EvaluationService {

    private final EvaluationModelRegistry modelRegistry;
    private final EvaluationDataset dataset;
    private final EvaluationMetricCalculator metricCalculator;
    private final ArtifactRegistry artifactRegistry;
    private final EvaluationRepository repository;
    private final Map<EvaluationCaseType, EvaluationCaseExecutor> executors = new EnumMap<>(EvaluationCaseType.class);

    public EvaluationService(EvaluationModelRegistry modelRegistry, EvaluationDataset dataset,
                             EvaluationMetricCalculator metricCalculator, ArtifactRegistry artifactRegistry,
                             EvaluationRepository repository, ModelDrivenCaseExecutor modelExecutor,
                             KnowledgeRetrievalExecutor knowledgeExecutor, JobCasesExecutor jobExecutor,
                             CitationGroundingExecutor citationExecutor,
                             ReplayConsistencyExecutor replayExecutor) {
        this.modelRegistry = modelRegistry;
        this.dataset = dataset;
        this.metricCalculator = metricCalculator;
        this.artifactRegistry = artifactRegistry;
        this.repository = repository;
        executors.put(EvaluationCaseType.COMPLETE_SPEC, modelExecutor);
        executors.put(EvaluationCaseType.MISSING_PARAMETER, modelExecutor);
        executors.put(EvaluationCaseType.INVALID_PARAMETER, modelExecutor);
        executors.put(EvaluationCaseType.TOOL_SELECTION, modelExecutor);
        executors.put(EvaluationCaseType.TOOL_SECURITY, modelExecutor);
        executors.put(EvaluationCaseType.KNOWLEDGE_RETRIEVAL, knowledgeExecutor);
        executors.put(EvaluationCaseType.JOB_SUBMISSION, jobExecutor);
        executors.put(EvaluationCaseType.JOB_STATUS, jobExecutor);
        executors.put(EvaluationCaseType.JOB_CANCEL, jobExecutor);
        executors.put(EvaluationCaseType.ARTIFACT_CITATION, citationExecutor);
        executors.put(EvaluationCaseType.REPORT_GROUNDING, citationExecutor);
        executors.put(EvaluationCaseType.REPLAY_CONSISTENCY, replayExecutor);
    }

    public EvaluationRun run(String datasetName, String modelName) {
        List<EvaluationCase> cases = dataset.require(datasetName);
        EvaluationModel model = modelRegistry.require(modelName);
        String evaluationId = "EVAL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Instant startedAt = Instant.now();

        List<EvaluationCaseResult> results = new ArrayList<>();
        for (EvaluationCase evalCase : cases) {
            results.add(executors.get(evalCase.caseType()).execute(evalCase, model));
        }
        List<EvaluationMetric> metrics = metricCalculator.compute(results);
        Instant completedAt = Instant.now();

        EvaluationReport report = new EvaluationReport(evaluationId, datasetName, model.name(),
                completedAt, "SUCCEEDED", metrics, results);
        EvaluationRun run = new EvaluationRun(evaluationId, datasetName, model.name(), "SUCCEEDED",
                startedAt, completedAt, results, metrics);
        try {
            artifactRegistry.writeJson(evaluationId, ArtifactType.EVAL_CASE_RESULTS,
                    "eval-case-results.json", results);
            artifactRegistry.writeJson(evaluationId, ArtifactType.EVAL_REPORT,
                    "eval-report.json", report);
        } catch (RuntimeException e) {
            // The run itself succeeded; artifact registration failure is recorded on the run.
            run = new EvaluationRun(evaluationId, datasetName, model.name(), "SUCCEEDED_WITH_ARTIFACT_ERROR",
                    startedAt, completedAt, results, metrics);
        }
        repository.save(run);
        return run;
    }

    public EvaluationRun get(String evaluationId) {
        return repository.findById(evaluationId)
                .orElseThrow(() -> new NoSuchElementException("Evaluation not found: " + evaluationId));
    }

    public List<EvaluationRun> list() { return repository.findAll(); }

    public EvaluationReport report(String evaluationId) {
        EvaluationRun run = get(evaluationId);
        return new EvaluationReport(run.evaluationId(), run.datasetName(), run.modelName(),
                run.completedAt(), run.status(), run.metrics(), run.results());
    }

    public EvaluationComparison compare(String baselineEvaluationId, String candidateEvaluationId) {
        EvaluationRun baseline = get(baselineEvaluationId);
        EvaluationRun candidate = get(candidateEvaluationId);
        if (!baseline.datasetName().equals(candidate.datasetName())) {
            throw new EvaluationException("Paired comparison requires the same dataset: baseline uses '"
                    + baseline.datasetName() + "' but candidate uses '" + candidate.datasetName() + "'");
        }
        Map<String, EvaluationCaseResult> baselineById = byCaseId(baseline);
        Map<String, EvaluationCaseResult> candidateById = byCaseId(candidate);
        List<String> regressed = new ArrayList<>();
        List<String> newlyPassed = new ArrayList<>();
        for (String caseId : baselineById.keySet()) {
            boolean baselinePassed = baselineById.get(caseId).passed();
            boolean candidatePassed = candidateById.get(caseId) != null && candidateById.get(caseId).passed();
            if (baselinePassed && !candidatePassed) regressed.add(caseId);
            if (!baselinePassed && candidatePassed) newlyPassed.add(caseId);
        }
        Map<String, EvaluationMetric> baselineMetrics = byName(baseline.metrics());
        Map<String, EvaluationMetric> candidateMetrics = byName(candidate.metrics());
        List<EvaluationComparison.MetricDelta> deltas = new ArrayList<>();
        for (EvaluationMetric metric : baseline.metrics()) {
            EvaluationMetric candidateMetric = candidateMetrics.get(metric.metricName());
            double candidateValue = candidateMetric == null ? 0 : candidateMetric.value();
            deltas.add(new EvaluationComparison.MetricDelta(metric.metricName(),
                    metric.value(), candidateValue, candidateValue - metric.value()));
        }
        boolean anyRegression = !regressed.isEmpty();
        boolean degraded = deltas.stream().anyMatch(delta -> delta.delta() < -1.0e-12);
        boolean releaseAllowed = !anyRegression && !degraded;
        String message = releaseAllowed
                ? "Candidate run has no regressed cases and no degraded metric; release is allowed."
                : "Candidate run regressed on " + regressed + " and degraded metrics: "
                        + deltas.stream().filter(delta -> delta.delta() < -1.0e-12)
                        .map(EvaluationComparison.MetricDelta::metricName).toList();
        EvaluationComparison comparison = new EvaluationComparison(baselineEvaluationId,
                candidateEvaluationId, baseline.datasetName(), deltas, regressed, newlyPassed,
                releaseAllowed, message, Instant.now());
        try {
            artifactRegistry.writeJson(candidateEvaluationId, ArtifactType.EVAL_COMPARISON,
                    "eval-comparison.json", comparison);
        } catch (RuntimeException ignored) {
            // Comparison artifacts are best-effort bookkeeping; the response is authoritative.
        }
        return comparison;
    }

    private Map<String, EvaluationCaseResult> byCaseId(EvaluationRun run) {
        Map<String, EvaluationCaseResult> byId = new LinkedHashMap<>();
        for (EvaluationCaseResult result : run.results()) byId.put(result.caseId(), result);
        return byId;
    }

    private Map<String, EvaluationMetric> byName(List<EvaluationMetric> metrics) {
        Map<String, EvaluationMetric> byName = new LinkedHashMap<>();
        for (EvaluationMetric metric : metrics) byName.put(metric.metricName(), metric);
        return byName;
    }
}
