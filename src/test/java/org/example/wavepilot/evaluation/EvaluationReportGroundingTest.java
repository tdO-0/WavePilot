package org.example.wavepilot.evaluation;

import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.report.ExperimentReportData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationReportGroundingTest {

    private static final double TOLERANCE = 1.0e-9;

    @TempDir Path root;

    @Test
    void everyNumericConclusionValueMatchesItsCitationSource() throws Exception {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationFixtureFactory factory = new EvaluationFixtureFactory(
                stack.jobRepository(), stack.registry(), stack.mapper());
        ExperimentJob job = factory.buildSucceededJob();
        ExperimentReportData data = stack.assembler().assemble(job,
                stack.registry().listByJobId(job.getJobId()));

        for (var conclusion : data.conclusions()) {
            if (conclusion.metricValue() == null) continue;
            assertTrue(!conclusion.citationIds().isEmpty(),
                    "numeric conclusion must carry a citation: " + conclusion.conclusionId());
            for (String citationId : conclusion.citationIds()) {
                var citation = data.citations().stream()
                        .filter(candidate -> candidate.citationId().equals(citationId))
                        .findFirst().orElseThrow();
                assertTrue(citation.value() instanceof Number,
                        "citation source value must be numeric: " + citationId);
                assertEquals(conclusion.metricValue().doubleValue(),
                        ((Number) citation.value()).doubleValue(), TOLERANCE,
                        "conclusion " + conclusion.conclusionId() + " must match its citation");
            }
        }
        assertTrue(stack.citationValidator().validate(job.getJobId(), data).valid());
    }

    @Test
    void tamperedSummaryIsCaughtByTheJavaRecomputation() throws Exception {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationFixtureFactory factory = new EvaluationFixtureFactory(
                stack.jobRepository(), stack.registry(), stack.mapper());
        ExperimentJob job = factory.buildSucceededJob();
        ExperimentReportData data = stack.assembler().assemble(job,
                stack.registry().listByJobId(job.getJobId()));
        assertTrue(stack.citationValidator().validate(job.getJobId(), data).valid());

        var summaryArtifact = stack.registry().listByJobId(job.getJobId()).stream()
                .filter(record -> record.type() == ArtifactType.SUMMARY_JSON).findFirst().orElseThrow();
        Path summaryPath = stack.registry().resolveVerified(summaryArtifact.artifactId());
        String summaryJson = Files.readString(summaryPath)
                .replace("\"minAccuracy\" : 0.5", "\"minAccuracy\" : 0.9");
        Files.writeString(summaryPath, summaryJson);

        // Java recomputes min/max/mean from the CSV; a tampered summary is rejected before
        // any citation can be produced.
        org.example.wavepilot.report.ReportDataAssembler.ReportDataException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.wavepilot.report.ReportDataAssembler.ReportDataException.class,
                        () -> stack.assembler().assemble(job, stack.registry().listByJobId(job.getJobId())));
        assertTrue(exception.getMessage().contains("minAccuracy"));
    }

    @Test
    void groundingCasesPassUnderTheReferenceModel() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");
        EvaluationCaseResult citationCase = run.results().stream()
                .filter(result -> result.caseId().equals("C-019")).findFirst().orElseThrow();
        assertTrue(citationCase.passed());
        assertEquals("CITATIONS_VERIFIED", citationCase.actualResult());
        EvaluationCaseResult groundingCase = run.results().stream()
                .filter(result -> result.caseId().equals("C-021")).findFirst().orElseThrow();
        assertTrue(groundingCase.passed());
        assertEquals("GROUNDED", groundingCase.actualResult());
    }
}
