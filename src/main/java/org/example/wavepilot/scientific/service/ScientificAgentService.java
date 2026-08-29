package org.example.wavepilot.scientific.service;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.retrieval.HybridRetrievalService;
import org.example.wavepilot.knowledge.retrieval.RetrievalResponse;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.AgentRunState;
import org.example.wavepilot.scientific.model.ArtifactSnapshot;
import org.example.wavepilot.scientific.model.ExecutionRecord;
import org.example.wavepilot.scientific.model.ExecutionStatus;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.Observation;
import org.example.wavepilot.scientific.model.ReplanDecision;
import org.example.wavepilot.scientific.model.ScientificExperimentPlan;
import org.example.wavepilot.scientific.model.VerificationResult;
import org.example.wavepilot.scientific.repository.AgentRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Goal -> Plan -> Retrieve -> Execute -> Observe -> Verify -> Replan/Finish. */
@Service
public class ScientificAgentService {
    private static final long POLL_MILLIS = 20;

    private final AgentRunRepository repository;
    private final ScientificPlanner planner;
    private final HybridRetrievalService retrievalService;
    private final ExperimentService experimentService;
    private final ScientificVerifier verifier;
    private final BoundedScientificReplanner replanner;
    private final ArtifactRegistry artifactRegistry;
    private final ConcurrentMap<String, Object> runLocks = new ConcurrentHashMap<>();

    public ScientificAgentService(AgentRunRepository repository, ScientificPlanner planner,
                                  HybridRetrievalService retrievalService,
                                  ExperimentService experimentService, ScientificVerifier verifier,
                                  BoundedScientificReplanner replanner,
                                  ArtifactRegistry artifactRegistry) {
        this.repository = repository;
        this.planner = planner;
        this.retrievalService = retrievalService;
        this.experimentService = experimentService;
        this.verifier = verifier;
        this.replanner = replanner;
        this.artifactRegistry = artifactRegistry;
    }

    public AgentRun createCheckpoint(ExperimentGoal goal) {
        AgentRun run = new AgentRun(goal);
        return repository.save(run);
    }

    public AgentRun start(ExperimentGoal goal) {
        return resume(createCheckpoint(goal).getRunId());
    }

    public AgentRun resume(String runId) {
        synchronized (runLocks.computeIfAbsent(runId, ignored -> new Object())) {
            AgentRun run = get(runId);
            if (run.getState().isTerminal()) return run;
            try {
                validateInitialBoundaries(run);
                while (!run.getState().isTerminal()) {
                    if (stopForBudget(run)) break;
                    int iteration = activeIteration(run);
                    executeIteration(run, iteration);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                run.finish(AgentRunState.CANCELLED, "Scientific Agent execution interrupted");
                checkpoint(run);
            } catch (RuntimeException e) {
                if (!run.getState().isTerminal()) {
                    run.finish(AgentRunState.FAILED, "Scientific Agent execution failed: " + e.getMessage());
                }
                checkpoint(run);
            } finally {
                if (run.getState().isTerminal()) runLocks.remove(runId);
            }
            return run;
        }
    }

    public AgentRun get(String runId) {
        return repository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Scientific AgentRun not found: " + runId));
    }

    public List<AgentRun> list() { return repository.findAll(); }

    private void executeIteration(AgentRun run, int iteration) throws InterruptedException {
        ScientificExperimentPlan plan = run.getCurrentPlan();
        if (plan == null || plan.iteration() != iteration) {
            run.setState(AgentRunState.PLANNING);
            long started = System.nanoTime();
            plan = planner.plan(run, iteration);
            run.getTrace().addPlanningLatency(elapsed(started));
            run.setCurrentPlan(plan);
            run.incrementIteration();
            checkpoint(run);
        }

        String retrieveStep = plan.steps().get(0).stepId();
        if (!run.getCompletedSteps().contains(retrieveStep)) {
            run.setState(AgentRunState.RETRIEVING);
            run.setCurrentStep(retrieveStep);
            long started = System.nanoTime();
            try {
                RetrievalResponse response = retrievalService.search(new KnowledgeSearchRequest(
                        run.getGoal().description(), 5, null, run.getCurrentSpec().experimentType()));
                run.getTrace().addRetrieval(elapsed(started), response.denseCandidateCount(),
                        response.sparseCandidateCount(), response.rerankLatencyMillis());
            } catch (RuntimeException unavailableKnowledgeStore) {
                // Retrieval is evidence enrichment. It cannot authorize or block execution;
                // the ExperimentSpec still goes through deterministic validation below.
                run.getTrace().addRetrieval(elapsed(started), 0, 0, 0);
            }
            run.completeStep(retrieveStep);
            checkpoint(run);
        }

        Observation observation = observationFor(run, iteration);
        if (observation == null) observation = executeAndObserve(run, plan, iteration);

        String verifyStep = plan.steps().get(2).stepId();
        run.setState(AgentRunState.VERIFYING);
        run.setCurrentStep(verifyStep);
        long verifyStarted = System.nanoTime();
        VerificationResult result = verifier.verify(run.getGoal(), observation, run);
        run.getTrace().addVerificationLatency(elapsed(verifyStarted));
        run.addVerification(result);
        run.completeStep(verifyStep);
        checkpoint(run);
        if (result.passed()) {
            run.finish(AgentRunState.SUCCEEDED, "Goal satisfied by deterministically verified artifacts");
            checkpoint(run);
            return;
        }
        if (budgetReachedAfterIteration(run)) {
            run.finish(AgentRunState.BUDGET_EXHAUSTED,
                    "Goal was not satisfied before iteration/experiment/model/token budget was exhausted");
            checkpoint(run);
            return;
        }

        run.setState(AgentRunState.REPLANNING);
        run.setCurrentStep(plan.planId() + "-REPLAN");
        ReplanDecision decision = replanner.replan(run.getGoal(), run.getCurrentSpec(), iteration, run);
        run.addReplan(decision);
        if (!decision.replan() || decision.nextSpec() == null) {
            run.finish(AgentRunState.FAILED, "Safe replan unavailable: " + decision.reason());
            checkpoint(run);
            return;
        }
        run.setCurrentSpec(decision.nextSpec());
        run.setCurrentPlan(null);
        run.setCurrentStep(null);
        checkpoint(run);
    }

    private Observation executeAndObserve(AgentRun run, ScientificExperimentPlan plan, int iteration)
            throws InterruptedException {
        String executeStep = plan.steps().get(1).stepId();
        String executionId = "EXEC-" + run.getRunId().substring(4) + "-I" + iteration;
        ExecutionRecord record = run.execution(executionId);
        ExperimentJob job;
        boolean reused = record != null && record.jobId() != null;
        if (reused) {
            job = experimentService.get(record.jobId());
        } else {
            if (run.getExperimentCount() >= run.getGoal().budget().maxExperiments()) {
                throw new BudgetLimitException("experiment budget exhausted before execution");
            }
            ValidationResult validation = experimentService.parseAndValidate(run.getCurrentSpec());
            if (!validation.valid()) {
                throw new IllegalArgumentException("Planner/Replanner ExperimentSpec rejected by Java Validator: "
                        + String.join("; ", validation.errors()));
            }
            record = new ExecutionRecord(executionId, executionId, iteration, executeStep,
                    ExecutionStatus.PENDING, null, 0, false, null, Instant.now(), null);
            run.putExecution(record);
            run.setState(AgentRunState.EXECUTING);
            run.setCurrentStep(executeStep);
            checkpoint(run);
            job = experimentService.create(run.getCurrentSpec(), executionId);
            run.incrementExperiment();
            record = record.with(ExecutionStatus.RUNNING, job.getJobId(), 0, false, null);
            run.putExecution(record);
            checkpoint(run);
        }

        long executionStarted = System.nanoTime();
        ExperimentStatus terminal = awaitTerminal(run, job.getJobId());
        run.getTrace().addExecutionLatency(elapsed(executionStarted));
        if (terminal != ExperimentStatus.SUCCEEDED) {
            record = record.with(terminal == ExperimentStatus.CANCELLED
                            ? ExecutionStatus.FAILED : ExecutionStatus.FAILED,
                    job.getJobId(), record.retryCount(), reused,
                    "Experiment job ended with " + terminal);
            run.putExecution(record);
            checkpoint(run);
            throw new IllegalStateException(record.error());
        }
        record = record.with(ExecutionStatus.COMPLETED, job.getJobId(), record.retryCount(), reused, null);
        run.putExecution(record);
        run.completeStep(executeStep);
        run.setState(AgentRunState.OBSERVING);
        checkpoint(run);

        ExperimentService.ExperimentSummaryView summary = experimentService.readExperimentSummary(job.getJobId());
        List<ArtifactRecord> records = experimentService.artifacts(job.getJobId());
        Observation observation = new Observation(iteration, executionId, job.getJobId(),
                summary.values(), records.stream().map(ArtifactSnapshot::from).toList(),
                records.stream().allMatch(ArtifactRecord::validated), Instant.now());
        run.addObservation(observation);
        checkpoint(run);
        return observation;
    }

    private ExperimentStatus awaitTerminal(AgentRun run, String jobId) throws InterruptedException {
        int statusRetries = 0;
        while (true) {
            if (run.timedOut()) {
                experimentService.cancel(jobId);
                run.finish(AgentRunState.TIMED_OUT, "AgentRun timeout expired during experiment execution");
                checkpoint(run);
                return ExperimentStatus.CANCELLED;
            }
            ExperimentStatus status;
            try {
                status = experimentService.progress(jobId).status();
                statusRetries = 0;
            } catch (RuntimeException transientStatusFailure) {
                if (statusRetries >= run.getGoal().budget().maxRetries()) throw transientStatusFailure;
                statusRetries++;
                run.setRetryCount(run.getRetryCount() + 1);
                checkpoint(run);
                Thread.sleep(POLL_MILLIS * statusRetries);
                continue;
            }
            if (status.isTerminal()) return status;
            Thread.sleep(POLL_MILLIS);
        }
    }

    private int activeIteration(AgentRun run) {
        if (run.getCurrentPlan() != null) {
            int planIteration = run.getCurrentPlan().iteration();
            boolean verified = run.getVerificationResults().stream()
                    .anyMatch(value -> value.iteration() == planIteration);
            if (!verified) return planIteration;
        }
        return run.getIterationCount() + 1;
    }

    private Observation observationFor(AgentRun run, int iteration) {
        return run.getObservations().stream().filter(value -> value.iteration() == iteration)
                .reduce((first, second) -> second).orElse(null);
    }

    private void validateInitialBoundaries(AgentRun run) {
        ValidationResult validation = experimentService.parseAndValidate(run.getCurrentSpec());
        if (!validation.valid()) {
            throw new IllegalArgumentException("Initial ExperimentSpec rejected by Java Validator: "
                    + String.join("; ", validation.errors()));
        }
        if (!replanner.withinBounds(run.getCurrentSpec(), run.getGoal().parameterBounds())) {
            throw new IllegalArgumentException("Initial ExperimentSpec is outside declared parameter boundaries");
        }
    }

    private boolean stopForBudget(AgentRun run) {
        if (run.timedOut()) {
            run.finish(AgentRunState.TIMED_OUT, "AgentRun timeout expired");
        } else if (run.getIterationCount() >= run.getGoal().budget().maxIterations()
                && run.getCurrentPlan() == null) {
            run.finish(AgentRunState.BUDGET_EXHAUSTED, "iteration budget exhausted");
        } else if (run.getExperimentCount() >= run.getGoal().budget().maxExperiments()
                && run.getCurrentPlan() == null) {
            run.finish(AgentRunState.BUDGET_EXHAUSTED, "experiment budget exhausted");
        } else if (run.getTrace().getModelCalls() > run.getGoal().budget().maxModelCalls()) {
            run.finish(AgentRunState.BUDGET_EXHAUSTED, "model-call budget exhausted");
        }
        if (run.getState().isTerminal()) checkpoint(run);
        return run.getState().isTerminal();
    }

    private boolean budgetReachedAfterIteration(AgentRun run) {
        long tokens = (run.getTrace().getInputTokens() == null ? 0 : run.getTrace().getInputTokens())
                + (run.getTrace().getOutputTokens() == null ? 0 : run.getTrace().getOutputTokens());
        return run.getIterationCount() >= run.getGoal().budget().maxIterations()
                || run.getExperimentCount() >= run.getGoal().budget().maxExperiments()
                || run.getTrace().getModelCalls() > run.getGoal().budget().maxModelCalls()
                || tokens > run.getGoal().budget().maxTokens();
    }

    private void checkpoint(AgentRun run) {
        repository.save(run);
        if (run.getState().isTerminal() && artifactRegistry.listByJobId(run.getRunId()).stream()
                .noneMatch(record -> record.type() == ArtifactType.AGENT_RUN_TRACE)) {
            artifactRegistry.writeJson(run.getRunId(), ArtifactType.AGENT_RUN_TRACE,
                    "agent-run-trace.json", run.getTrace());
        }
    }
    private long elapsed(long startedNanos) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static class BudgetLimitException extends RuntimeException {
        BudgetLimitException(String message) { super(message); }
    }
}
