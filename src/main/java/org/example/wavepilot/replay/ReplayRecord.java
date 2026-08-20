package org.example.wavepilot.replay;

import java.time.Instant;

/** Mutable state holder for one replay, mirroring the ExperimentJob idiom. */
public final class ReplayRecord {

    private final String replayId;
    private final String sourceJobId;
    private final String note;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile ReplayStatus status;
    private volatile String replayJobId;
    private volatile String failureReason;
    private volatile ReplayManifest manifest;
    private volatile ReplayComparisonResult comparison;

    public ReplayRecord(String replayId, String sourceJobId, String note) {
        this.replayId = replayId;
        this.sourceJobId = sourceJobId;
        this.note = note == null ? "" : note;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = ReplayStatus.RUNNING;
    }

    public synchronized void start(ReplayManifest manifest) {
        this.manifest = manifest;
        this.updatedAt = Instant.now();
    }

    public synchronized void setReplayJobId(String replayJobId) {
        this.replayJobId = replayJobId;
        this.updatedAt = Instant.now();
    }

    public synchronized void updateManifest(ReplayManifest manifest) {
        this.manifest = manifest;
        this.updatedAt = Instant.now();
    }

    public synchronized void complete(ReplayManifest manifest, ReplayComparisonResult comparison) {
        this.manifest = manifest;
        this.comparison = comparison;
        this.status = ReplayStatus.SUCCEEDED;
        this.failureReason = null;
        this.updatedAt = Instant.now();
    }

    public synchronized void fail(String reason) {
        this.status = ReplayStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public String getReplayId() { return replayId; }
    public String getSourceJobId() { return sourceJobId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public ReplayStatus getStatus() { return status; }
    public String getReplayJobId() { return replayJobId; }
    public String getFailureReason() { return failureReason; }
    public ReplayManifest getManifest() { return manifest; }
    public ReplayComparisonResult getComparison() { return comparison; }
}
