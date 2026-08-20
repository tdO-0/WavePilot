package org.example.wavepilot.autonomous;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable state of one autonomous session; the timeline is what the workbench renders.
 * The accessors are record-style (sessionId()...), which Jackson does not recognise as
 * bean getters; fields are exposed explicitly so the controller can serialise the session.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
public final class AutonomousSession {

    private final String sessionId;
    private final String request;
    private final Instant createdAt;
    private volatile AutonomousStatus status;
    private volatile Instant updatedAt;
    private volatile String error;
    private final List<AutonomousStep> steps = new ArrayList<>();
    private volatile Map<String, Object> pendingParams = Map.of();
    private volatile String pendingTemplateId;
    private volatile String pendingCandidateId;
    private volatile String jobId;
    private volatile String reportId;
    private volatile String modelName;
    private volatile boolean analyzeResults;
    private volatile String analysis;
    private volatile org.example.wavepilot.intent.ExperimentIntent experimentIntent;
    @JsonIgnore
    private final List<String> chatHistory = new ArrayList<>();

    public AutonomousSession(String request, String modelName) {
        this(request, modelName, false);
    }

    public AutonomousSession(String request, String modelName, boolean analyzeResults) {
        this.sessionId = "AUTO-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        this.request = request;
        this.modelName = modelName;
        this.analyzeResults = analyzeResults;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = AutonomousStatus.UNDERSTANDING_INTENT;
    }

    public synchronized void addStep(String role, String message, String toolName, String toolResult,
                                     AutonomousStatus status) {
        steps.add(new AutonomousStep("STEP-" + steps.size() + 1, role, message, toolName,
                toolResult, status, Instant.now()));
        updatedAt = Instant.now();
    }

    public synchronized void transition(AutonomousStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public synchronized void fail(String reason) {
        this.status = AutonomousStatus.FAILED;
        this.error = reason;
        this.updatedAt = Instant.now();
    }

    public synchronized void addChat(String turn) {
        chatHistory.add(turn);
    }

    public String sessionId() { return sessionId; }
    public String request() { return request; }
    public Instant createdAt() { return createdAt; }
    public AutonomousStatus status() { return status; }
    public Instant updatedAt() { return updatedAt; }
    public String error() { return error; }
    public boolean analyzeResults() { return analyzeResults; }
    public String analysis() { return analysis; }
    public List<AutonomousStep> steps() { return List.copyOf(steps); }
    public Map<String, Object> pendingParams() { return pendingParams; }
    public String pendingTemplateId() { return pendingTemplateId; }
    public String pendingCandidateId() { return pendingCandidateId; }
    public String jobId() { return jobId; }
    public String reportId() { return reportId; }
    public String modelName() { return modelName; }
    public List<String> chatHistory() { return List.copyOf(chatHistory); }

    public synchronized void setPendingParams(Map<String, Object> pendingParams) {
        this.pendingParams = pendingParams == null ? Map.of() : Map.copyOf(pendingParams);
    }

    public synchronized void setPendingTemplateId(String pendingTemplateId) {
        this.pendingTemplateId = pendingTemplateId;
    }

    public synchronized void setPendingCandidateId(String pendingCandidateId) {
        this.pendingCandidateId = pendingCandidateId;
    }

    public synchronized void setJobId(String jobId) { this.jobId = jobId; }
    public synchronized void setReportId(String reportId) { this.reportId = reportId; }
    public synchronized void setAnalysis(String analysis) { this.analysis = analysis; }
    public synchronized void setExperimentIntent(org.example.wavepilot.intent.ExperimentIntent intent) {
        this.experimentIntent = intent;
    }
    public org.example.wavepilot.intent.ExperimentIntent experimentIntent() { return experimentIntent; }
}
