package org.example.wavepilot.experiment.model;

import java.time.Instant;

public final class ExperimentJob {

    private final String jobId;
    private final ExperimentSpec spec;
    private final GenericExperimentSpec genericSpec;
    private final ExperimentPlan plan;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile ExperimentStatus status;
    private volatile ExperimentProgress progress;
    private volatile String externalJobId;
    private volatile String sourceJobId;
    private volatile String failureReason;

    public ExperimentJob(String jobId, ExperimentSpec spec, ExperimentPlan plan) {
        this(jobId, spec, null, plan);
    }

    /** Generic (declarative-template) job: carries a GenericExperimentSpec, no fake polar spec. */
    public ExperimentJob(String jobId, GenericExperimentSpec genericSpec, ExperimentPlan plan) {
        this(jobId, null, genericSpec, plan);
    }

    private ExperimentJob(String jobId, ExperimentSpec spec, GenericExperimentSpec genericSpec,
                          ExperimentPlan plan) {
        this.jobId = jobId;
        this.spec = spec;
        this.genericSpec = genericSpec;
        this.plan = plan;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = ExperimentStatus.CREATED;
        this.progress = new ExperimentProgress(jobId, status, 0, "CREATED", 0,
                plan == null ? 0 : plan.totalRuns(), "Experiment job created", this.createdAt);
    }

    /** 恢复构造器：从持久化快照重建任务（时间、状态、进度原样保留），供文件仓储重启加载。 */
    public ExperimentJob(String jobId, ExperimentSpec spec, ExperimentPlan plan,
                         Instant createdAt, Instant updatedAt, ExperimentStatus status,
                         ExperimentProgress progress, String externalJobId,
                         String sourceJobId, String failureReason) {
        this(jobId, spec, null, plan, createdAt, updatedAt, status, progress,
                externalJobId, sourceJobId, failureReason);
    }

    /** 恢复构造器（generic 任务）。 */
    public ExperimentJob(String jobId, GenericExperimentSpec genericSpec, ExperimentPlan plan,
                         Instant createdAt, Instant updatedAt, ExperimentStatus status,
                         ExperimentProgress progress, String externalJobId,
                         String sourceJobId, String failureReason) {
        this(jobId, null, genericSpec, plan, createdAt, updatedAt, status, progress,
                externalJobId, sourceJobId, failureReason);
    }

    private ExperimentJob(String jobId, ExperimentSpec spec, GenericExperimentSpec genericSpec,
                          ExperimentPlan plan, Instant createdAt, Instant updatedAt,
                          ExperimentStatus status, ExperimentProgress progress,
                          String externalJobId, String sourceJobId, String failureReason) {
        this.jobId = jobId;
        this.spec = spec;
        this.genericSpec = genericSpec;
        this.plan = plan;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.status = status;
        this.progress = progress;
        this.externalJobId = externalJobId;
        this.sourceJobId = sourceJobId;
        this.failureReason = failureReason;
    }

    public synchronized void changeStatus(ExperimentStatus nextStatus, String message) {
        this.status = nextStatus;
        this.updatedAt = Instant.now();
        this.progress = new ExperimentProgress(jobId, nextStatus, progress.progress(),
                nextStatus.name(), progress.completedRuns(), progress.totalRuns(), message, updatedAt);
    }

    public synchronized void updateProgress(int percentage, String stage, long completedRuns,
                                            long totalRuns, String message) {
        this.updatedAt = Instant.now();
        this.progress = new ExperimentProgress(jobId, status, percentage, stage,
                completedRuns, totalRuns, message, updatedAt);
    }

    public String getJobId() { return jobId; }
    public ExperimentSpec getSpec() { return spec; }
    public GenericExperimentSpec getGenericSpec() { return genericSpec; }
    public ExperimentPlan getPlan() { return plan; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public ExperimentStatus getStatus() { return status; }
    public ExperimentProgress getProgress() { return progress; }
    public String getExternalJobId() { return externalJobId; }
    public String getSourceJobId() { return sourceJobId; }
    public String getFailureReason() { return failureReason; }
    public void setExternalJobId(String externalJobId) { this.externalJobId = externalJobId; }
    public void setSourceJobId(String sourceJobId) { this.sourceJobId = sourceJobId; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
