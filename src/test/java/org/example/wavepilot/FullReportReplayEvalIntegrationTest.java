package org.example.wavepilot;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.report.ArtifactCitation;
import org.example.wavepilot.report.ExperimentReportDocument;
import org.example.wavepilot.report.ReportConclusion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report, replay and eval subsystems must interoperate on one real-format job:
 * report with citations, reproducible replay, and an eval run that reuses the same
 * platform chain.
 */
class FullReportReplayEvalIntegrationTest {

    @TempDir Path root;

    @Test
    void reportReplayAndEvalWorkTogetherOnOneJob() throws Exception {
        IntegrationTestSupport.Stack stack = IntegrationTestSupport.stack(root);
        ExperimentJob job = stack.experimentService().create(WavePilotTestFixtures.validSpec());
        assertEquals(ExperimentStatus.SUCCEEDED, stack.awaitJob(job.getJobId()).getStatus());

        // Report chain
        ExperimentReportDocument report = stack.reportService().generate(job.getJobId());
        assertNotNull(report.markdown());
        assertEquals("TEMPLATE", report.generatedBy(), "no model configured -> pure template report");
        for (ReportConclusion conclusion : report.conclusions()) {
            if (conclusion.metricValue() != null) {
                assertTrue(!conclusion.citationIds().isEmpty(),
                        "numeric conclusion must carry a citation: " + conclusion.conclusionId());
            }
        }
        var citations = stack.reportService().citations(job.getJobId());
        assertTrue(citations.size() > 20, "the 13-column contract must yield many citations");
        for (ArtifactCitation citation : citations) {
            assertNotNull(citation.artifactSha256());
            assertTrue(citation.jobId().equals(job.getJobId()), "no cross-job citations");
        }

        // Replay chain on the same job
        var replayRecord = stack.replayService().startReplay(job.getJobId(), null);
        var replayDone = stack.awaitReplay(replayRecord.getReplayId());
        assertEquals(org.example.wavepilot.replay.ReplayStatus.SUCCEEDED, replayDone.getStatus());
        assertEquals(org.example.wavepilot.replay.ReplayComparisonResult.REPRODUCIBLE,
                replayDone.getComparison().verdict());
        // The replay job can also produce a report (all its artifacts are validated).
        var replayReport = stack.reportService().generate(replayDone.getReplayJobId());
        assertNotNull(replayReport.markdown());
        assertTrue(replayReport.markdown().contains("algorithmValidated=false"));

        // Eval chain
        var baseline = stack.evaluationService().run("default", "stub-v1");
        var candidate = stack.evaluationService().run("default", "stub-v2");
        var comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());
        assertEquals(2, comparison.regressedCaseIds().size());
        assertTrue(!comparison.releaseAllowed());
        // Case-level results survive alongside the aggregate metrics.
        assertTrue(candidate.results().stream()
                .anyMatch(result -> result.caseId().equals("C-009") && !result.passed()));
    }
}
