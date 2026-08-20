package org.example.wavepilot;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.report.ExperimentReportData;
import org.example.wavepilot.report.ExperimentReportDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full offline chain, one job: structured spec -> Java validator -> ExperimentService ->
 * runner -> ResultValidator -> ArtifactRegistry -> ReportDataAssembler -> ReportCitationValidator
 * -> template report -> Replay -> Eval. No Milvus, MATLAB or DashScope.
 */
class FullWavePilotIntegrationTest {

    @TempDir Path root;

    @Test
    void structuredSpecRunsTheWholePlatformChainEndToEnd() throws Exception {
        IntegrationTestSupport.Stack stack = IntegrationTestSupport.stack(root);

        // 1. Java validation
        ExperimentSpec spec = WavePilotTestFixtures.validSpec();
        ValidationResult validation = stack.experimentService().parseAndValidate(spec);
        assertTrue(validation.valid(), "spec must pass Java validation: " + validation.errors());

        // 2. Job creation through the controlled runner
        ExperimentJob job = stack.experimentService().create(spec);
        ExperimentJob done = stack.awaitJob(job.getJobId());
        assertEquals(ExperimentStatus.SUCCEEDED, done.getStatus());

        // 3. ResultValidator accepted the artifacts
        var artifacts = stack.registry().listByJobId(job.getJobId());
        assertTrue(artifacts.stream().allMatch(ArtifactRecord::validated),
                "every artifact must be validated");
        assertTrue(artifacts.stream().anyMatch(record -> record.type() == ArtifactType.ACCURACY_CSV));
        assertTrue(artifacts.stream().anyMatch(record -> record.type() == ArtifactType.SUMMARY_JSON));

        // 4. Report data assembly + citation validation + template report
        ExperimentReportData data = stack.reportService().data(job.getJobId());
        assertTrue(stack.citationValidator().validate(job.getJobId(), data).valid());
        ExperimentReportDocument report = stack.reportService().generate(job.getJobId());
        assertNotNull(report.markdown());
        assertTrue(report.markdown().contains("SIMPLIFIED_BASELINE"));
        assertTrue(report.markdown().contains("algorithmValidated=false"));
        assertTrue(!data.citations().isEmpty());

        // 5. Replay produces an independent REPRODUCIBLE run
        var replayRecord = stack.replayService().startReplay(job.getJobId(), null);
        var replayDone = stack.awaitReplay(replayRecord.getReplayId());
        assertEquals(org.example.wavepilot.replay.ReplayStatus.SUCCEEDED, replayDone.getStatus());
        assertNotNull(replayDone.getComparison());
        assertEquals(org.example.wavepilot.replay.ReplayComparisonResult.REPRODUCIBLE,
                replayDone.getComparison().verdict());
        assertTrue(!replayDone.getReplayJobId().equals(job.getJobId()),
                "replay must run in a new independent job");

        // 6. Offline eval runs over the fixed dataset
        var evalRun = stack.evaluationService().run("default", "stub-v1");
        assertEquals(24, evalRun.results().size());
        assertEquals(24, evalRun.passedCases());
        assertTrue(evalRun.metrics().stream().anyMatch(metric ->
                metric.metricName().equals("overallTaskCompletionRate")
                        && metric.value() == 1.0));
    }
}
