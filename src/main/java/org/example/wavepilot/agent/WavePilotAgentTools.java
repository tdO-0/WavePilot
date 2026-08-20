package org.example.wavepilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.agent.spec.ExperimentSpecParseResult;
import org.example.wavepilot.agent.spec.ExperimentSpecParseStatus;
import org.example.wavepilot.agent.spec.ExperimentSpecParser;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.knowledge.KnowledgeService;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.template.TemplateCatalogService;
import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.example.wavepilot.template.smoke.CandidateSmokeService;
import org.example.wavepilot.template.validation.CandidateValidationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WavePilotAgentTools {

    private final KnowledgeService knowledgeService;
    private final ExperimentSpecParser specParser;
    private final ExperimentService experimentService;
    private final ObjectMapper objectMapper;
    private final String runnerType;
    private final TemplateCatalogService templateCatalog;
    private final TemplateGenerationService templateGeneration;
    private final CandidateValidationService candidateValidation;
    private final CandidateSmokeService candidateSmoke;

    public WavePilotAgentTools(KnowledgeService knowledgeService, ExperimentSpecParser specParser,
                               ExperimentService experimentService, ObjectMapper objectMapper,
                               @Value("${wavepilot.runner.type:mock}") String runnerType,
                               TemplateCatalogService templateCatalog,
                               TemplateGenerationService templateGeneration,
                               CandidateValidationService candidateValidation,
                               CandidateSmokeService candidateSmoke) {
        this.knowledgeService = knowledgeService;
        this.specParser = specParser;
        this.experimentService = experimentService;
        this.objectMapper = objectMapper;
        this.runnerType = runnerType;
        this.templateCatalog = templateCatalog;
        this.templateGeneration = templateGeneration;
        this.candidateValidation = candidateValidation;
        this.candidateSmoke = candidateSmoke;
    }

    @Tool(name = "searchExperimentKnowledge", description = "Search communication theory, standards, recipes and failure cases. Results contain stable KB citations.")
    public String searchExperimentKnowledge(
            @ToolParam(description = "Semantic query") String query,
            @ToolParam(required = false, description = "Optional document type") String documentType,
            @ToolParam(required = false, description = "Optional experiment type") String experimentType,
            @ToolParam(required = false, description = "Maximum results, 1 to 20") Integer topK) {
        return json(knowledgeService.search(new KnowledgeSearchRequest(query, topK,
                enumValue(DocumentType.class, documentType), enumValue(ExperimentType.class, experimentType))));
    }

    @Tool(name = "createExperimentSpec", description = "Extract an ExperimentSpec from natural language. Missing fields are returned as clarification questions and are never invented.")
    public String createExperimentSpec(@ToolParam(description = "User experiment request") String message) {
        return json(specParser.parse(message));
    }

    @Tool(name = "validateExperimentSpec", description = "Run the deterministic Java ExperimentSpecValidator. Invalid specs cannot be submitted.")
    public String validateExperimentSpec(@ToolParam(description = "ExperimentSpec JSON") String specJson) {
        return json(specParser.validateJson(specJson));
    }

    @Tool(name = "createExperimentPlan", description = "Preview the controlled configured-runner experiment plan after Java validation; this does not submit a task.")
    public String createExperimentPlan(@ToolParam(description = "Validated ExperimentSpec JSON") String specJson) {
        ExperimentSpec spec = requireComplete(specParser.validateJson(specJson));
        ExperimentPlan plan = experimentService.previewPlan(spec);
        return json(Map.of("mock", isMock(), "plan", plan));
    }

    @Tool(name = "submitExperiment", description = "Submit a spec through ExperimentService. The service validates again before the configured controlled runner is invoked.")
    public String submitExperiment(@ToolParam(description = "ExperimentSpec JSON") String specJson) {
        ExperimentSpec spec = requireComplete(specParser.validateJson(specJson));
        ExperimentJob job = experimentService.create(spec);
        return json(jobView(job));
    }

    @Tool(name = "getExperimentStatus", description = "Read current status and progress through ExperimentService; never infer completion.")
    public String getExperimentStatus(@ToolParam(description = "Experiment job ID") String jobId) {
        return json(jobView(experimentService.get(jobId)));
    }

    @Tool(name = "cancelExperiment", description = "Request cancellation through ExperimentService. The tool never modifies state directly.")
    public String cancelExperiment(@ToolParam(description = "Experiment job ID") String jobId) {
        return json(jobView(experimentService.cancel(jobId)));
    }

    @Tool(name = "listExperimentArtifacts", description = "List registered artifacts through ExperimentService. The mock field identifies whether outputs came from Mock Runner.")
    public String listExperimentArtifacts(@ToolParam(description = "Experiment job ID") String jobId) {
        List<ArtifactRecord> artifacts = experimentService.artifacts(jobId);
        return json(Map.of("mock", isMock(), "jobId", jobId, "artifacts", artifacts));
    }

    @Tool(name = "readExperimentSummary", description = "Read only the validated summary Artifact of a SUCCEEDED experiment through ExperimentService.")
    public String readExperimentSummary(@ToolParam(description = "Succeeded experiment job ID") String jobId) {
        return json(experimentService.readExperimentSummary(jobId));
    }

    @Tool(name = "compareExperiments", description = "Compare two SUCCEEDED experiments using validated summary Artifacts only and preserve their mock boundary.")
    public String compareExperiments(
            @ToolParam(description = "First succeeded job ID") String firstJobId,
            @ToolParam(description = "Second succeeded job ID") String secondJobId) {
        return json(experimentService.compareExperiments(firstJobId, secondJobId));
    }

    @Tool(name = "listExperimentTemplates", description = "List the formal experiment template catalog: templateId, experimentTypeId, activeVersion, source, status, operationalValidated, algorithmValidated and classification.")
    public String listExperimentTemplates() {
        return json(templateCatalog.listTemplates(null, null, null, null, null, null));
    }

    @Tool(name = "getExperimentTemplate", description = "Read one template's active version, versions, parameters, outputs, metrics, replay strategy and validation boundaries.")
    public String getExperimentTemplate(@ToolParam(description = "Template ID") String templateId) {
        return json(templateCatalog.templateDetail(templateId));
    }

    @Tool(name = "listTemplateCandidates", description = "List generated template candidates with their lifecycle status, security findings and smoke state.")
    public String listTemplateCandidates() {
        return json(templateCatalog.listCandidates().stream().map(this::candidateView).toList());
    }

    @Tool(name = "getTemplateCandidate", description = "Read one candidate's status, security findings, smoke report, assumptions and unresolved questions. Candidates are never ACTIVE by themselves.")
    public String getTemplateCandidate(@ToolParam(description = "Candidate ID") String candidateId) {
        return json(candidateView(templateCatalog.candidate(candidateId)));
    }

    @Tool(name = "generateTemplateCandidate", description = "Generate a candidate template package from a natural-language request. The candidate must be validated, smoke-tested and explicitly approved by a user before it can become ACTIVE.")
    public String generateTemplateCandidate(@ToolParam(description = "Natural-language template request") String request) {
        return json(candidateView(templateGeneration.generate(request)));
    }

    @Tool(name = "validateTemplateCandidate", description = "Run static validation (MATLAB security scan, definition schema, manifest consistency, hash integrity) on a candidate.")
    public String validateTemplateCandidate(@ToolParam(description = "Candidate ID") String candidateId) {
        return json(candidateView(candidateValidation.validate(candidateId)));
    }

    @Tool(name = "requestTemplateSmoke", description = "Request a MATLAB smoke run of a candidate. Without a real MATLAB environment the report states the smoke was not executed; approval still requires an explicit user action.")
    public String requestTemplateSmoke(@ToolParam(description = "Candidate ID") String candidateId) {
        return json(candidateView(candidateSmoke.smoke(candidateId)));
    }

    private Map<String, Object> candidateView(TemplateCandidate candidate) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("candidateId", candidate.candidateId());
        view.put("templateId", candidate.templateId());
        view.put("experimentTypeId", candidate.experimentTypeId());
        view.put("displayName", candidate.displayName());
        view.put("version", candidate.version());
        view.put("status", candidate.status());
        view.put("source", candidate.source());
        view.put("securityFindings", candidate.securityFindings());
        view.put("smokeReport", candidate.smokeReport());
        view.put("realSmokeExecuted", candidate.realSmokeExecuted());
        view.put("assumptions", candidate.assumptions());
        view.put("unresolvedQuestions", candidate.unresolvedQuestions());
        view.put("failureReason", candidate.failureReason());
        view.put("approvalRequired", true);
        return view;
    }

    private ExperimentSpec requireComplete(ExperimentSpecParseResult result) {
        if (result.parseStatus() != ExperimentSpecParseStatus.COMPLETE || result.experimentSpec() == null) {
            throw new IllegalArgumentException("ExperimentSpec is not valid: "
                    + String.join("; ", result.validationResult().errors()));
        }
        return result.experimentSpec();
    }

    private Map<String, Object> jobView(ExperimentJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mock", isMock());
        view.put("jobId", job.getJobId());
        view.put("status", job.getStatus());
        view.put("progress", job.getProgress());
        view.put("failureReason", job.getFailureReason());
        return view;
    }

    private boolean isMock() { return "mock".equalsIgnoreCase(runnerType); }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value.trim().toUpperCase());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Agent tool result", e);
        }
    }
}
