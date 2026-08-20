package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workbench must drive a full replay: start, poll the new job and show the comparison. */
class FrontendReplayContractTest {

    @Test
    void replayButtonStartsReplayOnTheSelectedJob() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("startReplay"), "replay start handler missing");
        assertTrue(app.contains("/replay'"), "replay endpoint missing");
        assertTrue(app.contains("源任务必须 SUCCEEDED"), "source job precondition hint missing");
    }

    @Test
    void replayStatusIsPolledUntilTerminal() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("pollReplay"), "replay polling missing");
        assertTrue(app.contains("record.status !== 'RUNNING'"), "terminal polling condition missing");
        assertTrue(app.contains("renderReplayComparison"), "comparison rendering missing");
    }

    @Test
    void theReplayComparisonShowsVerdictMetricsAndTolerance() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("comparison.verdict"), "verdict display missing");
        assertTrue(app.contains("comparison.withinTolerance"), "tolerance display missing");
        assertTrue(app.contains("comparison.metrics"), "per-metric display missing");
        assertTrue(app.contains("最大绝对差"), "max abs diff label missing");
    }

    @Test
    void failedReplaysAreShownWithTheirReason() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("record.status === 'FAILED'"), "failed replay state missing");
        assertTrue(app.contains("record.failureReason"), "failure reason display missing");
        assertTrue(app.contains("Replay 失败"), "replay failure hint missing");
    }

    @Test
    void newReplayJobsAppearInTheJobListAsIndependentJobs() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("sourceJobId"), "replay-to-source linkage display missing");
        assertTrue(app.contains("新任务"), "replay job id display missing");
        assertTrue(app.contains("源任务"), "source job display missing");
    }
}
