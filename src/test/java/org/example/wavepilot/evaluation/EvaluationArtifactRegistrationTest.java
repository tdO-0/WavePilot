package org.example.wavepilot.evaluation;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationArtifactRegistrationTest {

    @TempDir Path root;

    @Test
    void runRegistersReportAndCaseResultsArtifacts() throws Exception {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        List<ArtifactRecord> records = stack.registry().listByJobId(run.evaluationId());
        ArtifactRecord reportRecord = records.stream()
                .filter(record -> record.type() == ArtifactType.EVAL_REPORT).findFirst().orElseThrow();
        ArtifactRecord resultsRecord = records.stream()
                .filter(record -> record.type() == ArtifactType.EVAL_CASE_RESULTS).findFirst().orElseThrow();

        EvaluationReport report = stack.mapper().readValue(
                stack.registry().resolveVerified(reportRecord.artifactId()).toFile(),
                EvaluationReport.class);
        assertEquals(run.evaluationId(), report.evaluationId());
        assertEquals("stub-v1", report.modelName());
        assertEquals(11, report.metrics().size());
        assertEquals(24, report.results().size());
        assertEquals(24, report.passedCases());

        List<EvaluationCaseResult> results = stack.mapper().readValue(
                stack.registry().resolveVerified(resultsRecord.artifactId()).toFile(),
                stack.mapper().getTypeFactory().constructCollectionType(List.class, EvaluationCaseResult.class));
        assertEquals(24, results.size());
        assertEquals(run.results().get(0).caseId(), results.get(0).caseId());
    }

    @Test
    void compareRegistersAnEvaluationComparisonArtifact() throws Exception {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun baseline = stack.evaluationService().run("default", "stub-v1");
        EvaluationRun candidate = stack.evaluationService().run("default", "stub-v2");

        EvaluationComparison comparison = stack.evaluationService()
                .compare(baseline.evaluationId(), candidate.evaluationId());

        ArtifactRecord comparisonRecord = stack.registry().listByJobId(candidate.evaluationId()).stream()
                .filter(record -> record.type() == ArtifactType.EVAL_COMPARISON).findFirst().orElseThrow();
        EvaluationComparison stored = stack.mapper().readValue(
                stack.registry().resolveVerified(comparisonRecord.artifactId()).toFile(),
                EvaluationComparison.class);
        assertEquals(comparison.baselineEvaluationId(), stored.baselineEvaluationId());
        assertEquals(comparison.candidateEvaluationId(), stored.candidateEvaluationId());
        assertNotNull(stored.metricDeltas());
        assertEquals(2, stored.regressedCaseIds().size());
    }

    @Test
    void evaluationJsonNeverExposesLocalAbsolutePaths() throws Exception {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");
        String json = stack.mapper().writeValueAsString(run);
        assertTrue(!json.contains(root.toAbsolutePath().toString().replace("\\", "/"))
                && !json.contains("C:\\") && !json.contains("D:\\"),
                "evaluation JSON must not expose local file system paths");
    }
}
