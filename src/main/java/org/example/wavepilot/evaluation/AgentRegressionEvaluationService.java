package org.example.wavepilot.evaluation;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.knowledge.evaluation.RetrievalEvaluationReport;
import org.example.wavepilot.knowledge.evaluation.RetrievalEvaluationService;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.example.wavepilot.replay.ReplayComparisonResult;
import org.example.wavepilot.replay.ReplayRecord;
import org.example.wavepilot.replay.ReplayService;
import org.example.wavepilot.replay.ReplayStatus;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.example.wavepilot.scientific.service.ScientificAgentService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Paired regression evaluation derived from real AgentRun, retrieval-eval and replay records. */
@Service
public class AgentRegressionEvaluationService {
    private final ScientificAgentService agentService;
    private final RetrievalEvaluationService retrievalService;
    private final ReplayService replayService;
    private final ExperimentSpecValidator specValidator;
    private final ArtifactRegistry artifactRegistry;
    private final ConcurrentMap<String, AgentRegressionEvaluationReport> reports = new ConcurrentHashMap<>();

    public AgentRegressionEvaluationService(ScientificAgentService agentService,
                                            RetrievalEvaluationService retrievalService,
                                            ReplayService replayService,
                                            ExperimentSpecValidator specValidator,
                                            ArtifactRegistry artifactRegistry) {
        this.agentService = agentService;
        this.retrievalService = retrievalService;
        this.replayService = replayService;
        this.specValidator = specValidator;
        this.artifactRegistry = artifactRegistry;
    }

    public AgentRegressionEvaluationReport evaluate(String runId, String retrievalEvaluationId,
                                                     String replayId) {
        return evaluate(runId, retrievalEvaluationId, replayId, AgentEvaluationProfile.CANDIDATE);
    }

    public AgentRegressionEvaluationReport evaluate(String runId, String retrievalEvaluationId,
                                                     String replayId, AgentEvaluationProfile profile) {
        AgentRun run = agentService.get(runId);
        RetrievalEvaluationReport retrieval = retrievalService.get(retrievalEvaluationId);
        ReplayRecord replay = replayService.get(replayId);
        List<AgentEvaluationResult> results = new ArrayList<>();
        results.add(result(AgentEvaluationDimension.TASK_SUCCESS,
                run.getState() == org.example.wavepilot.scientific.model.AgentRunState.SUCCEEDED,
                "AgentRun state=" + run.getState()));
        boolean planValid = validPlan(run);
        results.add(result(AgentEvaluationDimension.PLAN_VALIDITY, planValid,
                "controlled plan steps=" + (run.getCurrentPlan() == null ? 0 : run.getCurrentPlan().steps().size())));
        boolean toolsCorrect = run.getExecutions().size() == run.getExperimentCount()
                && run.getExecutions().stream().allMatch(execution -> execution.stepId().endsWith("-EXECUTE"));
        results.add(result(AgentEvaluationDimension.TOOL_SELECTION_CORRECTNESS, toolsCorrect,
                "controlled executions=" + run.getExecutions().size()));
        ValidationResult validation = specValidator.validate(run.getCurrentSpec());
        results.add(result(AgentEvaluationDimension.EXPERIMENT_SPEC_VALIDITY, validation.valid(),
                validation.valid() ? "Java Validator accepted final spec" : String.join("; ", validation.errors())));
        RetrievalStrategy retrievalStrategy = profile == AgentEvaluationProfile.BASELINE
                ? RetrievalStrategy.DENSE_ONLY : RetrievalStrategy.HYBRID_RRF_MODEL_RERANK;
        double citationRate = retrieval.metrics().get(retrievalStrategy).citationHitRate();
        double recall = retrieval.metrics().get(retrievalStrategy).recallAt5();
        boolean artifactCitations = run.getObservations().stream().flatMap(value -> value.artifacts().stream())
                .allMatch(snapshot -> snapshot.validated() && snapshot.sha256() != null && !snapshot.sha256().isBlank());
        results.add(result(AgentEvaluationDimension.CITATION_VALIDITY,
                citationRate > 0 && citationRate + 1.0e-12 >= recall && artifactCitations,
                "citationHitRate=" + citationRate + ", recallAt5=" + recall
                        + ", artifact snapshots grounded=" + artifactCitations));
        results.add(result(AgentEvaluationDimension.RETRIEVAL_QUALITY, recall > 0,
                "hybrid Recall@K=" + recall));
        boolean grounded = !run.getVerificationResults().isEmpty()
                && run.getVerificationResults().stream().allMatch(value -> value.grounded() && value.artifactsComplete());
        results.add(result(AgentEvaluationDimension.GROUNDED_RESULT_CONSISTENCY, grounded,
                "grounded verification count=" + run.getVerificationResults().size()));
        boolean replaySuccess = replay.getStatus() == ReplayStatus.SUCCEEDED
                && replay.getComparison() != null && replay.getComparison().consistent();
        results.add(result(AgentEvaluationDimension.REPLAY_SUCCESS, replaySuccess,
                "replay status=" + replay.getStatus() + ", verdict="
                        + (replay.getComparison() == null ? "pending" : replay.getComparison().verdict())));
        boolean terminated = run.getState().isTerminal()
                && run.getIterationCount() <= run.getGoal().budget().maxIterations()
                && run.getExperimentCount() <= run.getGoal().budget().maxExperiments();
        results.add(result(AgentEvaluationDimension.LOOP_TERMINATION, terminated,
                "iterations=" + run.getIterationCount() + ", experiments=" + run.getExperimentCount()));
        double invalidToolRate = (double) run.getTrace().getInvalidToolCallCount()
                / Math.max(1, run.getExecutions().size() + run.getTrace().getInvalidToolCallCount());
        boolean noInvalidTools = invalidToolRate == 0;
        results.add(result(AgentEvaluationDimension.INVALID_TOOL_CALL_RATE, noInvalidTools,
                "invalidToolCallRate=" + invalidToolRate));
        double invalidSpecRate = (double) run.getTrace().getInvalidExperimentSpecCount()
                / Math.max(1, 1 + run.getReplanDecisions().size()
                + run.getTrace().getInvalidExperimentSpecCount());
        boolean noInvalidSpecs = invalidSpecRate == 0;
        results.add(result(AgentEvaluationDimension.INVALID_EXPERIMENT_SPEC_RATE, noInvalidSpecs,
                "invalidExperimentSpecRate=" + invalidSpecRate));
        long acceptedReplans = run.getReplanDecisions().stream().filter(value -> value.replan()
                && value.nextSpec() != null && specValidator.validate(value.nextSpec()).valid()).count();
        boolean replansValid = acceptedReplans == run.getReplanDecisions().size();
        results.add(result(AgentEvaluationDimension.REPLAN_SUCCESS_RATE, replansValid,
                "acceptedValidatedReplans=" + acceptedReplans + "/" + run.getReplanDecisions().size()));
        results.add(result(AgentEvaluationDimension.ARTIFACT_GROUNDING_SUCCESS, artifactCitations && grounded,
                "validated artifacts and grounded verifications=" + (artifactCitations && grounded)));
        long recoveryAttempts = run.getExecutions().stream().filter(value -> value.reused()).count();
        long recoveredCompleted = run.getExecutions().stream().filter(value -> value.reused()
                && value.status() == org.example.wavepilot.scientific.model.ExecutionStatus.COMPLETED).count();
        double recoveryRate = recoveryAttempts == 0 ? 1.0 : (double) recoveredCompleted / recoveryAttempts;
        results.add(result(AgentEvaluationDimension.RECOVERY_SUCCESS, recoveryRate >= 1.0 - 1.0e-12,
                "recoverySuccessRate=" + recoveryRate + ", attempts=" + recoveryAttempts));
        long distinctExecutions = run.getExecutions().stream().map(value -> value.executionId()).distinct().count();
        long duplicates = run.getExecutions().size() - distinctExecutions
                + run.getTrace().getDuplicateExecutionCount();
        results.add(result(AgentEvaluationDimension.DUPLICATE_EXECUTION_RATE, duplicates == 0,
                "duplicateExecutions=" + duplicates));
        boolean latencyBounded = run.getTrace().getTotalLatencyMillis() >= 0
                && run.getTrace().getTotalLatencyMillis() <= run.getGoal().budget().timeout().toMillis();
        results.add(result(AgentEvaluationDimension.TOTAL_LATENCY, latencyBounded,
                "totalLatencyMillis=" + run.getTrace().getTotalLatencyMillis()));
        boolean modelCallsBounded = run.getTrace().getModelCalls() <= run.getGoal().budget().maxModelCalls();
        results.add(result(AgentEvaluationDimension.MODEL_CALLS, modelCallsBounded,
                "modelCalls=" + run.getTrace().getModelCalls()));
        long passed = results.stream().filter(AgentEvaluationResult::passed).count();
        double replanRate = run.getReplanDecisions().isEmpty() ? 1.0
                : (double) acceptedReplans / run.getReplanDecisions().size();
        AgentEvaluationTelemetry telemetry = new AgentEvaluationTelemetry(planValid ? 1 : 0,
                terminated ? 1 : 0, invalidToolRate, invalidSpecRate,
                replanRate, artifactCitations && grounded ? 1 : 0, recoveryRate,
                duplicates == 0 ? 0 : (double) duplicates / Math.max(1, run.getExecutions().size()),
                recall, citationRate, run.getTrace().getTotalLatencyMillis(), run.getTrace().getModelCalls());
        String id = "AGENTEVAL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        AgentRegressionEvaluationReport report = new AgentRegressionEvaluationReport(id, runId,
                retrievalEvaluationId, replayId, profile, results, passed, results.size(),
                (double) passed / results.size(), telemetry, Instant.now(),
                "Software regression evaluation over actual offline records; not scientific performance validation.");
        reports.put(id, report);
        artifactRegistry.writeJson(id, ArtifactType.AGENT_EVAL_REPORT, "agent-eval-report.json", report);
        return report;
    }

    public AgentRegressionEvaluationReport get(String evaluationId) {
        AgentRegressionEvaluationReport report = reports.get(evaluationId);
        if (report == null) throw new NoSuchElementException("Agent evaluation not found: " + evaluationId);
        return report;
    }

    public AgentRegressionComparison compare(String baselineId, String candidateId) {
        AgentRegressionEvaluationReport baseline = get(baselineId);
        AgentRegressionEvaluationReport candidate = get(candidateId);
        Map<AgentEvaluationDimension, Boolean> baselineBy = byDimension(baseline);
        Map<AgentEvaluationDimension, Boolean> candidateBy = byDimension(candidate);
        List<AgentEvaluationDimension> regressed = new ArrayList<>();
        List<AgentEvaluationDimension> improved = new ArrayList<>();
        for (AgentEvaluationDimension dimension : AgentEvaluationDimension.values()) {
            boolean before = baselineBy.getOrDefault(dimension, false);
            boolean after = candidateBy.getOrDefault(dimension, false);
            if (before && !after) regressed.add(dimension);
            if (!before && after) improved.add(dimension);
        }
        double delta = candidate.successRate() - baseline.successRate();
        AgentRegressionComparison comparison = new AgentRegressionComparison(baselineId, candidateId,
                regressed, improved, delta, regressed.isEmpty() && delta >= -1.0e-12, Instant.now());
        artifactRegistry.writeJson(candidateId, ArtifactType.AGENT_EVAL_COMPARISON,
                "agent-eval-comparison.json", comparison);
        return comparison;
    }

    private AgentEvaluationResult result(AgentEvaluationDimension dimension, boolean passed, String evidence) {
        return new AgentEvaluationResult(dimension, passed, evidence);
    }

    private Map<AgentEvaluationDimension, Boolean> byDimension(AgentRegressionEvaluationReport report) {
        Map<AgentEvaluationDimension, Boolean> values = new EnumMap<>(AgentEvaluationDimension.class);
        report.results().forEach(result -> values.put(result.dimension(), result.passed()));
        return values;
    }

    private boolean validPlan(AgentRun run) {
        if (run.getCurrentPlan() == null || run.getCurrentPlan().steps().size() < 3) return false;
        List<ScientificCapability> capabilities = run.getCurrentPlan().steps().stream()
                .map(step -> step.capability()).toList();
        Set<ScientificCapability> unique = new HashSet<>(capabilities);
        if (unique.size() != capabilities.size()
                || !org.example.wavepilot.scientific.service.ScientificPlanSchemaValidator.REGISTERED
                .containsAll(unique)
                || !unique.containsAll(Set.of(ScientificCapability.RETRIEVE_EVIDENCE,
                ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT,
                ScientificCapability.VERIFY_GROUNDED_RESULT))) return false;
        int retrieve = capabilities.indexOf(ScientificCapability.RETRIEVE_EVIDENCE);
        int execute = capabilities.indexOf(ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT);
        int analyze = capabilities.indexOf(ScientificCapability.ANALYZE_RESULT);
        int verify = capabilities.indexOf(ScientificCapability.VERIFY_GROUNDED_RESULT);
        int replan = capabilities.indexOf(ScientificCapability.REPLAN_EXPERIMENT);
        boolean ordered = retrieve < execute && execute < verify
                && (analyze < 0 || execute < analyze && analyze < verify)
                && (replan < 0 || verify < replan);
        return ordered && run.getCurrentPlan().steps().stream()
                .allMatch(step -> step.capability() != ScientificCapability.EXECUTE_VALIDATED_EXPERIMENT
                        || step.experimentSpec() != null);
    }
}
