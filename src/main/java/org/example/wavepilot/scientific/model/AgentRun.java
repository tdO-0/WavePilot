package org.example.wavepilot.scientific.model;

import org.example.wavepilot.experiment.model.ExperimentSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Serializable checkpoint containing all state needed to resume the controlled loop. */
public class AgentRun {
    private String runId;
    private ExperimentGoal goal;
    private ExperimentSpec currentSpec;
    private ScientificExperimentPlan currentPlan;
    private String currentStep;
    private List<String> completedSteps = new ArrayList<>();
    private List<ExecutionRecord> executions = new ArrayList<>();
    private List<Observation> observations = new ArrayList<>();
    private List<VerificationResult> verificationResults = new ArrayList<>();
    private List<ReplanDecision> replanDecisions = new ArrayList<>();
    private int retryCount;
    private int replanCount;
    private int iterationCount;
    private int experimentCount;
    private AgentRunState state;
    private Instant createdAt;
    private Instant updatedAt;
    private String terminalReason;
    private AgentRunTrace trace = new AgentRunTrace();

    public AgentRun() { }

    public AgentRun(ExperimentGoal goal) {
        this.runId = "RUN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        this.goal = goal;
        this.currentSpec = goal.initialSpec();
        this.state = AgentRunState.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public ExperimentGoal getGoal() { return goal; }
    public void setGoal(ExperimentGoal value) { goal = value; }
    public ExperimentSpec getCurrentSpec() { return currentSpec; }
    public void setCurrentSpec(ExperimentSpec value) { currentSpec = value; touch(); }
    public ScientificExperimentPlan getCurrentPlan() { return currentPlan; }
    public void setCurrentPlan(ScientificExperimentPlan value) { currentPlan = value; touch(); }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String value) { currentStep = value; touch(); }
    public List<String> getCompletedSteps() { return List.copyOf(completedSteps); }
    public void setCompletedSteps(List<String> value) { completedSteps = mutable(value); }
    public List<ExecutionRecord> getExecutions() { return List.copyOf(executions); }
    public void setExecutions(List<ExecutionRecord> value) { executions = mutable(value); }
    public List<Observation> getObservations() { return List.copyOf(observations); }
    public void setObservations(List<Observation> value) { observations = mutable(value); }
    public List<VerificationResult> getVerificationResults() { return List.copyOf(verificationResults); }
    public void setVerificationResults(List<VerificationResult> value) { verificationResults = mutable(value); }
    public List<ReplanDecision> getReplanDecisions() { return List.copyOf(replanDecisions); }
    public void setReplanDecisions(List<ReplanDecision> value) { replanDecisions = mutable(value); }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int value) { retryCount = value; }
    public int getReplanCount() { return replanCount; }
    public void setReplanCount(int value) { replanCount = value; }
    public int getIterationCount() { return iterationCount; }
    public void setIterationCount(int value) { iterationCount = value; }
    public int getExperimentCount() { return experimentCount; }
    public void setExperimentCount(int value) { experimentCount = value; }
    public AgentRunState getState() { return state; }
    public void setState(AgentRunState value) { state = value; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public String getTerminalReason() { return terminalReason; }
    public void setTerminalReason(String value) { terminalReason = value; touch(); }
    public AgentRunTrace getTrace() { return trace; }
    public void setTrace(AgentRunTrace value) { trace = value == null ? new AgentRunTrace() : value; }

    public void completeStep(String stepId) { if (!completedSteps.contains(stepId)) completedSteps.add(stepId); touch(); }
    public void putExecution(ExecutionRecord record) {
        executions.removeIf(existing -> existing.executionId().equals(record.executionId()));
        executions.add(record); touch();
    }
    public ExecutionRecord execution(String executionId) {
        return executions.stream().filter(value -> value.executionId().equals(executionId)).findFirst().orElse(null);
    }
    public void addObservation(Observation value) { observations.add(value); touch(); }
    public Observation latestObservation() { return observations.isEmpty() ? null : observations.get(observations.size() - 1); }
    public void addVerification(VerificationResult value) { verificationResults.add(value); touch(); }
    public void addReplan(ReplanDecision value) { replanDecisions.add(value); replanCount++; trace.setReplanCount(replanCount); touch(); }
    public void incrementIteration() { iterationCount++; touch(); }
    public void incrementExperiment() { experimentCount++; touch(); }
    public boolean timedOut() {
        return createdAt != null && Instant.now().isAfter(createdAt.plus(goal.budget().timeout()));
    }
    public void finish(AgentRunState finalState, String reason) {
        state = finalState;
        terminalReason = reason;
        updatedAt = Instant.now();
        trace.setFinalTaskStatus(finalState.name());
        trace.setTotalLatencyMillis(createdAt == null ? 0 : Duration.between(createdAt, updatedAt).toMillis());
    }

    private void touch() { updatedAt = Instant.now(); }
    private static <T> List<T> mutable(List<T> value) {
        return value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
