package org.example.wavepilot.report;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ReportService {

    private final ExperimentJobRepository jobs;
    private final ArtifactRegistry artifacts;
    private final ReportDataAssembler assembler;
    private final ReportCitationValidator citationValidator;
    private final TemplateExperimentReportGenerator templateGenerator;
    private final ControlledReportAgent reportAgent;
    private final Optional<ReportLanguageModel> model;
    private final DeclarativeResultInterpreter genericInterpreter;
    private final GenericExperimentReportGenerator genericGenerator;
    private final Optional<GroundedReportLanguageModel> groundedModel;
    private final GroundedAnalysisValidator groundedValidator;
    private final ConcurrentMap<String, ExperimentReportDocument> reports = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArtifactCitation> citations = new ConcurrentHashMap<>();

    public ReportService(ExperimentJobRepository jobs, ArtifactRegistry artifacts,
                         ReportDataAssembler assembler, ReportCitationValidator citationValidator,
                         TemplateExperimentReportGenerator templateGenerator,
                         ControlledReportAgent reportAgent, Optional<ReportLanguageModel> model) {
        this(jobs, artifacts, assembler, citationValidator, templateGenerator, reportAgent, model,
                null, null, Optional.empty(), new GroundedAnalysisValidator());
    }

    public ReportService(ExperimentJobRepository jobs, ArtifactRegistry artifacts,
                         ReportDataAssembler assembler, ReportCitationValidator citationValidator,
                         TemplateExperimentReportGenerator templateGenerator,
                         ControlledReportAgent reportAgent, Optional<ReportLanguageModel> model,
                         DeclarativeResultInterpreter genericInterpreter,
                         GenericExperimentReportGenerator genericGenerator) {
        this(jobs, artifacts, assembler, citationValidator, templateGenerator, reportAgent, model,
                genericInterpreter, genericGenerator, Optional.empty(), new GroundedAnalysisValidator());
    }

    @Autowired
    public ReportService(ExperimentJobRepository jobs, ArtifactRegistry artifacts,
                         ReportDataAssembler assembler, ReportCitationValidator citationValidator,
                         TemplateExperimentReportGenerator templateGenerator,
                         ControlledReportAgent reportAgent, Optional<ReportLanguageModel> model,
                         DeclarativeResultInterpreter genericInterpreter,
                         GenericExperimentReportGenerator genericGenerator,
                         Optional<GroundedReportLanguageModel> groundedModel,
                         GroundedAnalysisValidator groundedValidator) {
        this.jobs = jobs;
        this.artifacts = artifacts;
        this.assembler = assembler;
        this.citationValidator = citationValidator;
        this.templateGenerator = templateGenerator;
        this.reportAgent = reportAgent;
        this.model = model;
        this.genericInterpreter = genericInterpreter;
        this.genericGenerator = genericGenerator;
        this.groundedModel = groundedModel;
        this.groundedValidator = groundedValidator;
    }

    public ExperimentReportDocument generate(String jobId) {
        ExperimentJob job = requireSucceeded(jobId);
        ExperimentReportDocument result;
        if (job.getGenericSpec() != null && genericInterpreter != null && genericGenerator != null) {
            // Generic (declarative-template) job: interpret the validated CSV through the
            // template definition and render the template-semantic report; when a grounded
            // LLM is configured, append its validated analysis.
            ExperimentResultData resultData = genericInterpreter.interpret(job, artifacts.listByJobId(jobId));
            ValidationResult validation = citationValidator.validate(resultData);
            if (!validation.valid()) throw new ReportValidationException(validation);
            result = genericGenerator.generate(resultData);
            if (groundedModel.isPresent()) {
                try {
                    GroundedReportLanguageModel.AnalysisDraft draft = groundedModel.get()
                            .analyze(GroundedAnalysisContext.from(resultData));
                    groundedValidator.validate(GroundedAnalysisContext.from(resultData),
                            draft.analysisMarkdown());
                    result = new ExperimentReportDocument(jobId, CitationStatus.VERIFIED, "REPORT_AGENT",
                            result.markdown() + "\n\n---\n\n## Agent 分析\n\n" + draft.analysisMarkdown(),
                            null, draft.conclusions(), resultData.citations(), Instant.now());
                } catch (RuntimeException e) {
                    // The deterministic template report stays authoritative when the LLM
                    // analysis fails the grounding checks; the failure is never hidden.
                    result = new ExperimentReportDocument(jobId, CitationStatus.VERIFIED,
                            "TEMPLATE_FALLBACK", result.markdown() + "\n\n---\n\n"
                                    + "（Agent 分析未通过数值防幻觉校验，已保留模板报告：" + e.getMessage() + "）",
                            null, resultData.conclusions(), resultData.citations(), Instant.now());
                }
            }
        } else {
            ExperimentReportData data = data(jobId);
            ValidationResult validation = citationValidator.validate(jobId, data);
            if (!validation.valid()) throw new ReportValidationException(validation);
            ExperimentReportDocument template = templateGenerator.generate(data);
            result = template;
            if (model.isPresent()) {
                try {
                    ReportLanguageModel.ReportAgentDraft draft = reportAgent.rewrite(model.get(), data, template);
                    result = new ExperimentReportDocument(jobId, CitationStatus.VERIFIED, "REPORT_AGENT",
                            draft.markdown(), data, draft.conclusions(), data.citations(), Instant.now());
                } catch (RuntimeException ignored) {
                    result = new ExperimentReportDocument(jobId, CitationStatus.VERIFIED,
                            "TEMPLATE_FALLBACK", template.markdown(), data,
                            data.conclusions(), data.citations(), Instant.now());
                }
            }
        }
        reports.put(jobId, result);
        if (result.data() != null) {
            result.data().citations().forEach(citation -> citations.put(citation.citationId(), citation));
        } else {
            // Generic report data carries its own citation list.
            result.citations().forEach(citation -> citations.put(citation.citationId(), citation));
        }
        return result;
    }

    public ExperimentReportDocument get(String jobId) {
        requireSucceeded(jobId);
        return Optional.ofNullable(reports.get(jobId))
                .orElseThrow(() -> new NoSuchElementException("Report has not been generated: " + jobId));
    }

    public ExperimentReportData data(String jobId) {
        ExperimentJob job = requireSucceeded(jobId);
        ExperimentReportData data = assembler.assemble(job, artifacts.listByJobId(jobId));
        data.citations().forEach(citation -> citations.put(citation.citationId(), citation));
        return data;
    }

    public ValidationResult validate(String jobId) {
        return citationValidator.validate(jobId, data(jobId));
    }

    public List<ArtifactCitation> citations(String jobId) {
        return data(jobId).citations();
    }

    public ArtifactCitation citation(String citationId) {
        if (!citations.containsKey(citationId)) {
            jobs.findAll().stream().filter(job -> job.getStatus() == ExperimentStatus.SUCCEEDED).forEach(job -> {
                if (citations.containsKey(citationId)) return;
                try { data(job.getJobId()); }
                catch (RuntimeException ignored) { /* A non-reportable legacy/mock job is skipped. */ }
            });
        }
        return Optional.ofNullable(citations.get(citationId))
                .orElseThrow(() -> new NoSuchElementException("Citation not found: " + citationId));
    }

    private ExperimentJob requireSucceeded(String jobId) {
        ExperimentJob job = jobs.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Experiment job not found: " + jobId));
        if (job.getStatus() != ExperimentStatus.SUCCEEDED)
            throw new IllegalStateException("Job must be SUCCEEDED before report generation: " + jobId);
        return job;
    }

    public static class ReportValidationException extends RuntimeException {
        private final ValidationResult validationResult;
        public ReportValidationException(ValidationResult result) {
            super("Report citation validation failed: " + String.join("; ", result.errors()));
            this.validationResult = result;
        }
        public ValidationResult getValidationResult() { return validationResult; }
    }
}
