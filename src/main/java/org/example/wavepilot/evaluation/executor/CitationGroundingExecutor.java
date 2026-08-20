package org.example.wavepilot.evaluation.executor;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.evaluation.EvaluationCase;
import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationFixtureFactory;
import org.example.wavepilot.evaluation.EvaluationModel;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.report.ExperimentReportData;
import org.example.wavepilot.report.ReportCitationValidator;
import org.example.wavepilot.report.ReportDataAssembler;
import org.springframework.stereotype.Component;

/**
 * Verifies the Phase 5A/5B report chain on a controlled fixture job: every citation must
 * resolve to a validated artifact with an unchanged hash, and every numeric conclusion must
 * be grounded in a source value.
 */
@Component
public class CitationGroundingExecutor implements EvaluationCaseExecutor {

    private final EvaluationFixtureFactory fixtureFactory;
    private final ArtifactRegistry registry;
    private final ReportDataAssembler assembler;
    private final ReportCitationValidator citationValidator;

    public CitationGroundingExecutor(EvaluationFixtureFactory fixtureFactory, ArtifactRegistry registry,
                                     ReportDataAssembler assembler, ReportCitationValidator citationValidator) {
        this.fixtureFactory = fixtureFactory;
        this.registry = registry;
        this.assembler = assembler;
        this.citationValidator = citationValidator;
    }

    @Override
    public EvaluationCaseResult execute(EvaluationCase evalCase, EvaluationModel model) {
        try {
            ExperimentJob job = fixtureFactory.buildSucceededJob();
            ExperimentReportData data = assembler.assemble(job, registry.listByJobId(job.getJobId()));
            ValidationResult validation = citationValidator.validate(job.getJobId(), data);
            boolean allGrounded = data.conclusions().stream()
                    .allMatch(conclusion -> conclusion.metricValue() == null
                            || !conclusion.citationIds().isEmpty());
            boolean passed = validation.valid() && allGrounded
                    && evalCase.expectedResult().startsWith(
                            evalCase.caseType() == org.example.wavepilot.evaluation.EvaluationCaseType.ARTIFACT_CITATION
                                    ? "CITATIONS_VERIFIED" : "GROUNDED");
            String actualResult;
            if (!validation.valid()) {
                actualResult = "INVALID: " + String.join("; ", validation.errors());
            } else if (!allGrounded) {
                actualResult = "UNGROUNDED: some conclusions have no citation";
            } else {
                actualResult = evalCase.caseType()
                        == org.example.wavepilot.evaluation.EvaluationCaseType.ARTIFACT_CITATION
                        ? "CITATIONS_VERIFIED" : "GROUNDED";
            }
            return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(), evalCase.description(),
                    evalCase.input(), evalCase.expectedResult(), evalCase.expectedTool(),
                    evalCase.forbiddenTools(), evalCase.expectedStatus(), evalCase.expectedFields(),
                    evalCase.tags(), passed, actualResult, null,
                    passed ? null : "citation/grounding validation failed: " + actualResult);
        } catch (RuntimeException e) {
            return EvaluationCaseResult.failed(evalCase, "Citation/grounding execution failed: " + e.getMessage());
        }
    }
}
