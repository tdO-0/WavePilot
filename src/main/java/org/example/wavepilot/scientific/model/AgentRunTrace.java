package org.example.wavepilot.scientific.model;

import org.example.wavepilot.modelrouting.ModelRoutingDecision;

import java.util.ArrayList;
import java.util.List;

public class AgentRunTrace {
    private long planningLatencyMillis;
    private long retrievalLatencyMillis;
    private int denseCandidateCount;
    private int sparseCandidateCount;
    private long rerankLatencyMillis;
    private int modelCalls;
    private Long inputTokens;
    private Long outputTokens;
    private long experimentExecutionLatencyMillis;
    private long verificationLatencyMillis;
    private int replanCount;
    private long totalLatencyMillis;
    private String finalTaskStatus;
    private List<ModelRoutingDecision> routingDecisions = new ArrayList<>();

    public AgentRunTrace() { }

    public long getPlanningLatencyMillis() { return planningLatencyMillis; }
    public void setPlanningLatencyMillis(long value) { planningLatencyMillis = value; }
    public long getRetrievalLatencyMillis() { return retrievalLatencyMillis; }
    public void setRetrievalLatencyMillis(long value) { retrievalLatencyMillis = value; }
    public int getDenseCandidateCount() { return denseCandidateCount; }
    public void setDenseCandidateCount(int value) { denseCandidateCount = value; }
    public int getSparseCandidateCount() { return sparseCandidateCount; }
    public void setSparseCandidateCount(int value) { sparseCandidateCount = value; }
    public long getRerankLatencyMillis() { return rerankLatencyMillis; }
    public void setRerankLatencyMillis(long value) { rerankLatencyMillis = value; }
    public int getModelCalls() { return modelCalls; }
    public void setModelCalls(int value) { modelCalls = value; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long value) { inputTokens = value; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long value) { outputTokens = value; }
    public long getExperimentExecutionLatencyMillis() { return experimentExecutionLatencyMillis; }
    public void setExperimentExecutionLatencyMillis(long value) { experimentExecutionLatencyMillis = value; }
    public long getVerificationLatencyMillis() { return verificationLatencyMillis; }
    public void setVerificationLatencyMillis(long value) { verificationLatencyMillis = value; }
    public int getReplanCount() { return replanCount; }
    public void setReplanCount(int value) { replanCount = value; }
    public long getTotalLatencyMillis() { return totalLatencyMillis; }
    public void setTotalLatencyMillis(long value) { totalLatencyMillis = value; }
    public String getFinalTaskStatus() { return finalTaskStatus; }
    public void setFinalTaskStatus(String value) { finalTaskStatus = value; }
    public List<ModelRoutingDecision> getRoutingDecisions() { return List.copyOf(routingDecisions); }
    public void setRoutingDecisions(List<ModelRoutingDecision> value) {
        routingDecisions = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public void addPlanningLatency(long value) { planningLatencyMillis += value; }
    public void addRetrieval(long latency, int dense, int sparse, long rerank) {
        retrievalLatencyMillis += latency;
        denseCandidateCount += dense;
        sparseCandidateCount += sparse;
        rerankLatencyMillis += rerank;
    }
    public void addExecutionLatency(long value) { experimentExecutionLatencyMillis += value; }
    public void addVerificationLatency(long value) { verificationLatencyMillis += value; }
    public void recordRouting(ModelRoutingDecision decision) {
        routingDecisions.add(decision);
        if (decision.modelCall()) modelCalls++;
        if (decision.inputTokens() != null) inputTokens = (inputTokens == null ? 0 : inputTokens) + decision.inputTokens();
        if (decision.outputTokens() != null) outputTokens = (outputTokens == null ? 0 : outputTokens) + decision.outputTokens();
    }
}
