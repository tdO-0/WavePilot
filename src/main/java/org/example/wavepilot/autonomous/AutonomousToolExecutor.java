package org.example.wavepilot.autonomous;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentProgress;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.GenericExperimentSpec;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.report.ExperimentReportData;
import org.example.wavepilot.report.ExperimentReportDocument;
import org.example.wavepilot.report.ReportConclusion;
import org.example.wavepilot.report.ReportService;
import org.example.wavepilot.template.TemplateCatalogService;
import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.TemplateStatus;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.example.wavepilot.template.smoke.CandidateSmokeService;
import org.example.wavepilot.template.validation.CandidateValidationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the whitelisted autonomous tools against the production services. Suspending
 * tools (requestParameterInput / requestTemplateApproval) never take a decision themselves:
 * they park the session and let the human act through the controller endpoints.
 */
@Component
public class AutonomousToolExecutor {

    public static final Set<String> WHITELIST = Set.of(
            "searchTemplates", "getTemplateDetail", "requestParameterInput", "submitSpec",
            "generateCandidate", "validateCandidate", "smokeCandidate",
            "requestTemplateApproval", "waitForJobCompletion", "getJobStatus", "cancelJob",
            "listArtifacts", "generateReport", "getCitations", "analyzeResult",
            "createReplay", "getReplayComparison", "runEval", "getEvalResult",
            "compareEval", "searchKnowledge", "finish");

    private static final long JOB_POLL_INTERVAL_MILLIS = 300;
    private static final long JOB_MAX_WAIT_MILLIS = 10 * 60 * 1000L;

    private final TemplateCatalogService templateCatalog;
    private final TemplateGenerationService generation;
    private final CandidateValidationService validation;
    private final CandidateSmokeService smoke;
    private final ExperimentService experimentService;
    private final ReportService reportService;
    private final org.example.wavepilot.replay.ReplayService replayService;
    private final org.example.wavepilot.evaluation.EvaluationService evaluationService;
    private final org.example.wavepilot.knowledge.KnowledgeService knowledgeService;
    private final ExperimentDefinitionParser definitionParser;
    private final CandidateTemplateRepository candidateRepository;
    private final ObjectMapper objectMapper;

    public AutonomousToolExecutor(TemplateCatalogService templateCatalog,
                                  TemplateGenerationService generation,
                                  CandidateValidationService validation,
                                  CandidateSmokeService smoke,
                                  ExperimentService experimentService,
                                  ReportService reportService,
                                  org.example.wavepilot.replay.ReplayService replayService,
                                  org.example.wavepilot.evaluation.EvaluationService evaluationService,
                                  org.example.wavepilot.knowledge.KnowledgeService knowledgeService,
                                  ExperimentDefinitionParser definitionParser,
                                  CandidateTemplateRepository candidateRepository,
                                  ObjectMapper objectMapper) {
        this.templateCatalog = templateCatalog;
        this.generation = generation;
        this.validation = validation;
        this.smoke = smoke;
        this.experimentService = experimentService;
        this.reportService = reportService;
        this.replayService = replayService;
        this.evaluationService = evaluationService;
        this.knowledgeService = knowledgeService;
        this.definitionParser = definitionParser;
        this.candidateRepository = candidateRepository;
        this.objectMapper = objectMapper;
    }

    public ToolOutcome execute(AutonomousSession session, String tool, Map<String, Object> arguments) {
        if (!WHITELIST.contains(tool)) {
            throw new IllegalArgumentException("未授权工具：" + tool + "；白名单：" + WHITELIST);
        }
        return switch (tool) {
            case "searchTemplates" -> searchTemplates(text(arguments, "query"));
            case "getTemplateDetail" -> getTemplateDetail(text(arguments, "templateId"));
            case "requestParameterInput" -> requestParameterInput(session, arguments);
            case "submitSpec" -> submitSpec(session, text(arguments, "specJson"));
            case "generateCandidate" -> generateCandidate(session, text(arguments, "request"));
            case "validateCandidate" -> validateCandidate(session, text(arguments, "candidateId"));
            case "smokeCandidate" -> smokeCandidate(session, text(arguments, "candidateId"));
            case "requestTemplateApproval" -> requestTemplateApproval(session, text(arguments, "candidateId"));
            case "waitForJobCompletion" -> waitForJobCompletion(session, text(arguments, "jobId"));
            case "getJobStatus" -> getJobStatus(session, text(arguments, "jobId"));
            case "cancelJob" -> cancelJob(session, text(arguments, "jobId"));
            case "listArtifacts" -> listArtifacts(session, text(arguments, "jobId"));
            case "generateReport" -> generateReport(session, text(arguments, "jobId"));
            case "getCitations" -> getCitations(session, text(arguments, "jobId"));
            case "analyzeResult" -> analyzeResult(session, text(arguments, "jobId"));
            case "createReplay" -> createReplay(session, text(arguments, "jobId"));
            case "getReplayComparison" -> getReplayComparison(session, text(arguments, "replayId"));
            case "runEval" -> runEval(session, text(arguments, "modelName"));
            case "getEvalResult" -> getEvalResult(session, text(arguments, "evaluationId"));
            case "compareEval" -> compareEval(session, text(arguments, "baselineEvaluationId"),
                    text(arguments, "candidateEvaluationId"));
            case "searchKnowledge" -> searchKnowledge(text(arguments, "query"));
            case "finish" -> finish(session, arguments);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private ToolOutcome searchTemplates(String query) {
        // Semantic matching against the user request: only templates whose id, display
        // name, experiment type or classification actually match the query are returned,
        // with the ACTIVE version and a match flag. Without a match the agent must create
        // a new candidate instead of reusing an unrelated template. An empty query is a
        // directory browse: list every usable ACTIVE template so the model can judge.
        String normalized = normalize(query);
        List<Map<String, Object>> views = new ArrayList<>();
        for (TemplateRecord record : templateCatalog.listTemplates(null, null, null, null, null, null)) {
            String haystack = normalize(record.templateId() + " " + record.experimentTypeId() + " "
                    + record.displayName() + " " + record.classification());
            if (!normalized.isEmpty() && !matches(normalized, haystack)) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("templateId", record.templateId());
            view.put("experimentTypeId", record.experimentTypeId());
            view.put("displayName", record.displayName());
            view.put("activeVersion", record.activeVersion());
            view.put("status", record.status().name());
            view.put("usable", record.status() == TemplateStatus.ACTIVE
                    && record.operationalValidated());
            view.put("operationalValidated", record.operationalValidated());
            view.put("algorithmValidated", record.algorithmValidated());
            views.add(view);
        }
        return new ToolOutcome(false, json(Map.of("query", query == null ? "" : query,
                "hasMatch", !views.isEmpty(), "templates", views)), false);
    }

    /** Lower-cased, whitespace-collapsed search string. */
    private static String normalize(String text) {
        if (text == null) return "";
        return text.trim().toLowerCase()
                .replaceAll("[，。？！?,.!；;：:]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * A template matches when the whole normalized query is a substring of its fields, or
     * when any meaningful query token (≥2 chars) hits them. This keeps "跑一个 qpsk 实验"
     * matching the qpsk template while "帮我新建一个模板" matches nothing.
     */
    private static boolean matches(String normalized, String haystack) {
        if (normalized.isEmpty()) return false;
        if (haystack.contains(normalized)) return true;
        for (String token : normalized.split(" ")) {
            if (token.length() < 2) continue;
            if (haystack.contains(token)) return true;
        }
        return false;
    }

    private ToolOutcome getTemplateDetail(String templateId) {
        TemplateCatalogService.TemplateDetailView detail = templateCatalog.templateDetail(templateId);
        return new ToolOutcome(false, json(Map.of(
                "templateId", templateId,
                "activeVersion", detail.active().activeVersion(),
                "parameters", detail.definition() == null ? List.of()
                        : detail.definition().parameters().stream().map(p -> Map.of(
                                "name", p.name(), "required", p.required(), "type", p.type(),
                                "defaultValue", p.defaultValue() == null ? "" : p.defaultValue(),
                                "description", p.description(), "unit", p.unit())).toList())), false);
    }

    /** Platform contract of the built-in polar experiment type when no template is involved. */
    private static final List<Map<String, Object>> BUILT_IN_POLAR_PARAMETERS = List.of(
            Map.of("name", "codeLengths", "required", true, "type", "STRING", "defaultValue", "32",
                    "description", "码长列表，逗号分隔（如 32,64）", "unit", "bits"),
            Map.of("name", "errorRateStart", "required", true, "type", "NUMBER", "defaultValue", 0,
                    "description", "错误率起始值", "unit", "ratio"),
            Map.of("name", "errorRateEnd", "required", true, "type", "NUMBER", "defaultValue", 0.1,
                    "description", "错误率结束值", "unit", "ratio"),
            Map.of("name", "errorRateStep", "required", true, "type", "NUMBER", "defaultValue", 0.01,
                    "description", "错误率步长", "unit", "ratio"),
            Map.of("name", "sampleCount", "required", true, "type", "INTEGER", "defaultValue", 20,
                    "description", "每个参数点的样本数", "unit", "codewords"),
            Map.of("name", "monteCarloTimes", "required", true, "type", "INTEGER", "defaultValue", 10,
                    "description", "每个参数点的重复次数", "unit", "trials"));

    private ToolOutcome requestParameterInput(AutonomousSession session, Map<String, Object> arguments) {
        String templateId = text(arguments, "templateId");
        String candidateId = text(arguments, "candidateId");
        // No template context at all: the model must first match an existing template
        // (searchTemplates) or create a candidate (generateCandidate). Reusing a fixed
        // parameter set here would fake a template match the user never made.
        if ((templateId == null || templateId.isBlank())
                && (candidateId == null || candidateId.isBlank())) {
            return new ToolOutcome(false,
                    "没有可用的模板上下文：请先调用 searchTemplates 按用户请求匹配已发布模板，"
                            + "没有匹配时调用 generateCandidate 新建模板，然后再收集参数", false);
        }
        List<Map<String, Object>> definitions = new ArrayList<>();
        ExperimentDefinition definition = null;
        if (templateId != null && !templateId.isBlank()) {
            definition = definitionOf(templateId);
        } else if (candidateId != null && !candidateId.isBlank()) {
            TemplateCandidate candidate = candidateRepository.findById(candidateId).orElse(null);
            if (candidate != null) {
                // The goal resumes with the published template after approval: carry the
                // candidate's templateId so the parameter step knows which template to use.
                templateId = candidate.templateId();
                try {
                    definition = definitionParser.parse(candidate.definitionYaml());
                } catch (Exception e) {
                    definition = null;
                }
            }
        }
        if (definition != null) {
            for (var p : definition.parameters()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", p.name());
                d.put("required", p.required());
                d.put("type", p.type().name());
                d.put("defaultValue", p.defaultValue() == null ? "" : p.defaultValue());
                d.put("description", p.description());
                d.put("unit", p.unit());
                definitions.add(d);
            }
        } else {
            // No template and no candidate: the built-in polar experiment type still has a
            // fixed platform parameter contract; never leave the human with an empty form.
            definitions.addAll(BUILT_IN_POLAR_PARAMETERS);
        }
        // The contract key is "parameters"; models often phrase it as "parameterNames".
        // Schema authority: only parameters that exist in the template definition are kept;
        // a model-invented parameter is never auto-created with a default NUMBER type.
        List<String> names = stringList(arguments, "parameters");
        if (names.isEmpty()) names = stringList(arguments, "parameterNames");
        for (String name : names) {
            boolean exists = definitions.stream().anyMatch(d -> name.equals(d.get("name")));
            if (!exists) {
                return new ToolOutcome(false,
                        "参数 " + name + " 不在模板 Schema 中。请先 getTemplateDetail 读取该模板"
                                + "真实支持的参数，再 requestParameterInput；不得发明参数。", false);
            }
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("templateId", templateId == null ? "" : templateId);
        pending.put("candidateId", candidateId == null ? "" : candidateId);
        pending.put("parameters", definitions);
        // Carry the template display name and ACTIVE version so the UI can show which
        // template (and version) the parameters belong to — never a fixed dialog.
        if (templateId != null && !templateId.isBlank()) {
            try {
                TemplateCatalogService.TemplateDetailView detail = templateCatalog.templateDetail(templateId);
                pending.put("templateDisplayName", detail.active().displayName());
                pending.put("version", detail.active().activeVersion());
            } catch (RuntimeException ignored) { /* template may have been rolled back */ }
        }
        session.setPendingParams(pending);
        session.setPendingTemplateId(templateId);
        session.transition(AutonomousStatus.WAITING_PARAMS);
        return new ToolOutcome(true, "已挂起等待用户填写参数：" + names, true);
    }

    private ToolOutcome submitSpec(AutonomousSession session, String specJson) {
        // Guardrail: the model can never hand-roll a spec. Only the spec the session layer
        // built from the user's dialog answers (see AutonomousSessionService.buildSpecFromParams)
        // is accepted; any specJson the model invents is ignored, however well-formed.
        Object pending = session.pendingParams().get("specJson");
        if (pending == null || String.valueOf(pending).isBlank()) {
            throw new IllegalArgumentException(
                    "没有可提交的 Spec：必须先通过 requestParameterInput 收集参数，"
                            + "由系统根据用户填写值组装 Spec；模型不得自行构造 specJson");
        }
        boolean modelProvidedSpec = specJson != null && !specJson.isBlank();
        // Generic (declarative-template) specs carry experimentTypeId without the legacy
        // polar experimentType; they must never masquerade as POLAR_CODE_K_IDENTIFICATION.
        ExperimentJob job;
        if (isGenericSpec(String.valueOf(pending))) {
            GenericExperimentSpec generic = parseGenericSpec(String.valueOf(pending));
            job = experimentService.create(generic);
        } else {
            ExperimentSpec spec = parseSpec(String.valueOf(pending));
            job = experimentService.create(spec);
        }
        session.setJobId(job.getJobId());
        session.setPendingParams(Map.of());
        session.transition(AutonomousStatus.RUNNING_EXPERIMENT);
        return new ToolOutcome(false, "实验已提交：" + job.getJobId() + "，状态 " + job.getStatus()
                + (modelProvidedSpec ? "（已忽略模型传入的 specJson，使用用户填写的参数）" : ""), false);
    }

    private boolean isGenericSpec(String specJson) {
        try {
            JsonNode node = objectMapper.readTree(specJson);
            return node.hasNonNull("experimentTypeId") && !node.has("experimentType");
        } catch (Exception e) {
            return false;
        }
    }

    private GenericExperimentSpec parseGenericSpec(String specJson) {
        try {
            return objectMapper.readValue(specJson, GenericExperimentSpec.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Generic Spec JSON 无法解析：" + e.getMessage());
        }
    }

    private ToolOutcome generateCandidate(AutonomousSession session, String request) {
        // Phase 7 guard: when the resolved intent is missing critical information the agent
        // must ask, never design a template from guesses ("做 OFDM 性能实验" must not silently
        // pick QPSK/64 subcarriers/AWGN and generate).
        org.example.wavepilot.intent.ExperimentIntent intent = session.experimentIntent();
        if (intent != null && intent.needsClarification()) {
            session.transition(AutonomousStatus.WAITING_CLARIFICATION);
            return new ToolOutcome(false,
                    "实验意图信息不足，需要先澄清：" + String.join("、", intent.missingCriticalInformation())
                            + "。请向用户提问，不要生成候选模板。", false);
        }
        TemplateCandidate candidate = intent != null
                ? generation.generate(intent, request)
                : generation.generate(request);
        session.setPendingCandidateId(candidate.candidateId());
        session.transition(AutonomousStatus.GENERATING_CANDIDATE);
        return new ToolOutcome(false, "候选已生成：" + candidate.candidateId()
                + "（" + candidate.templateId() + "，状态 " + candidate.status() + "）", false);
    }

    private ToolOutcome validateCandidate(AutonomousSession session, String candidateId) {
        TemplateCandidate candidate = validation.validate(candidateId);
        session.transition(AutonomousStatus.VALIDATING);
        return new ToolOutcome(false, "候选校验结果：" + candidate.status()
                + (candidate.failureReason() == null ? "" : "，" + candidate.failureReason()), false);
    }

    private ToolOutcome smokeCandidate(AutonomousSession session, String candidateId) {
        TemplateCandidate candidate = smoke.smoke(candidateId);
        session.transition(AutonomousStatus.SMOKING);
        return new ToolOutcome(false, "Smoke 结果：" + candidate.status()
                + "；真实执行=" + candidate.realSmokeExecuted()
                + (candidate.smokeReport() == null ? "" : "；" + candidate.smokeReport()), false);
    }

    private ToolOutcome requestTemplateApproval(AutonomousSession session, String candidateId) {
        session.setPendingCandidateId(candidateId);
        TemplateCandidate candidate = templateCatalog.candidate(candidateId);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("candidateId", candidateId);
        pending.put("templateId", candidate.templateId());
        pending.put("version", candidate.version());
        pending.put("status", candidate.status());
        pending.put("securityFindings", candidate.securityFindings());
        pending.put("smokeReport", candidate.smokeReport() == null ? "" : candidate.smokeReport());
        pending.put("realSmokeExecuted", candidate.realSmokeExecuted());
        session.setPendingParams(pending);
        session.transition(AutonomousStatus.WAITING_APPROVAL);
        return new ToolOutcome(true, "已挂起等待用户审批发布候选：" + candidateId, true);
    }

    private ToolOutcome waitForJobCompletion(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        long deadline = System.currentTimeMillis() + JOB_MAX_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ExperimentStatus status = experimentService.progress(jobId).status();
            if (status.isTerminal()) {
                String message = status == ExperimentStatus.SUCCEEDED
                        ? "实验 " + jobId + " 已成功" : "实验 " + jobId + " 结束：" + status;
                if (status == ExperimentStatus.SUCCEEDED) session.transition(AutonomousStatus.RUNNING_EXPERIMENT);
                return new ToolOutcome(false, message, false);
            }
            try {
                Thread.sleep(JOB_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ToolOutcome(false, "等待被打断", false);
            }
        }
        return new ToolOutcome(false, "等待超时（" + JOB_MAX_WAIT_MILLIS + "ms）", false);
    }

    /** Non-blocking job status query; never fabricates a terminal state. */
    private ToolOutcome getJobStatus(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        ExperimentProgress progress = experimentService.progress(jobId);
        return new ToolOutcome(false, "任务 " + jobId + " 状态：" + progress.status()
                + "，进度 " + progress.progress() + "%，参数点 " + progress.completedRuns()
                + "/" + progress.totalRuns() + "：" + progress.message(), false);
    }

    private ToolOutcome cancelJob(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        ExperimentJob job = experimentService.cancel(jobId);
        return new ToolOutcome(false, "已取消任务 " + jobId + "，当前状态：" + job.getStatus(), false);
    }

    private ToolOutcome listArtifacts(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        List<Map<String, Object>> views = experimentService.artifacts(jobId).stream().map(record -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("artifactId", record.artifactId());
            view.put("artifactType", record.artifactType());
            view.put("fileName", record.fileName());
            view.put("validated", record.validated());
            view.put("sha256", record.sha256());
            return view;
        }).toList();
        return new ToolOutcome(false, json(Map.of("jobId", jobId, "artifacts", views)), false);
    }

    private ToolOutcome getCitations(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        ExperimentReportDocument report = reportService.get(jobId);
        List<Map<String, Object>> citations = report.citations().stream().map(citation -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("citationId", citation.citationId());
            view.put("fieldName", citation.fieldName());
            view.put("rowReference", citation.rowReference());
            view.put("value", String.valueOf(citation.value()));
            view.put("description", citation.description());
            view.put("artifactId", citation.artifactId());
            view.put("verified", citation.artifactSha256() != null);
            return view;
        }).toList();
        return new ToolOutcome(false, json(Map.of("jobId", jobId, "citations", citations)), false);
    }

    private ToolOutcome createReplay(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        org.example.wavepilot.replay.ReplayRecord record =
                replayService.startReplay(jobId, new org.example.wavepilot.replay.ReplayRequest("agent goal replay"));
        return new ToolOutcome(false, "Replay 已创建：" + record.getReplayId()
                + "（源任务 " + record.getSourceJobId() + "）", false);
    }

    private ToolOutcome getReplayComparison(AutonomousSession session, String replayId) {
        org.example.wavepilot.replay.ReplayComparisonResult comparison = replayService.comparison(replayId);
        return new ToolOutcome(false, json(Map.of(
                "replayId", replayId,
                "verdict", comparison.verdict(),
                "consistent", comparison.consistent(),
                "message", comparison.message())), false);
    }

    private ToolOutcome runEval(AutonomousSession session, String modelName) {
        if (modelName == null || modelName.isBlank()) modelName = "stub-v1";
        org.example.wavepilot.evaluation.EvaluationRun run = evaluationService.run("default", modelName);
        return new ToolOutcome(false, "Eval 已运行：" + run.evaluationId() + "，模型 " + run.modelName()
                + "，状态 " + run.status(), false);
    }

    private ToolOutcome getEvalResult(AutonomousSession session, String evaluationId) {
        if (evaluationId == null || evaluationId.isBlank()) {
            throw new IllegalArgumentException("缺少 evaluationId（可先用 runEval 运行）");
        }
        org.example.wavepilot.evaluation.EvaluationRun run = evaluationService.get(evaluationId);
        long passed = run.results().stream().filter(result -> result.passed()).count();
        return new ToolOutcome(false, "Eval " + evaluationId + "：" + passed + "/" + run.results().size()
                + " 通过，状态 " + run.status(), false);
    }

    private ToolOutcome compareEval(AutonomousSession session, String baselineEvaluationId,
                                    String candidateEvaluationId) {
        if (baselineEvaluationId == null || baselineEvaluationId.isBlank()
                || candidateEvaluationId == null || candidateEvaluationId.isBlank()) {
            throw new IllegalArgumentException("compareEval 需要 baselineEvaluationId 与 candidateEvaluationId");
        }
        org.example.wavepilot.evaluation.EvaluationComparison comparison =
                evaluationService.compare(baselineEvaluationId, candidateEvaluationId);
        return new ToolOutcome(false, json(Map.of(
                "baseline", baselineEvaluationId,
                "candidate", candidateEvaluationId,
                "releaseAllowed", comparison.releaseAllowed(),
                "message", comparison.message(),
                "regressedCases", comparison.regressedCaseIds())), false);
    }

    private ToolOutcome searchKnowledge(String query) {
        if (query == null || query.isBlank()) return new ToolOutcome(false, "未提供检索词", false);
        List<Map<String, Object>> views = knowledgeService.search(
                        new org.example.wavepilot.knowledge.model.KnowledgeSearchRequest(
                                query, 3, null, null))
                .stream().map(result -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("documentType", result.documentType().name());
                    view.put("title", result.title());
                    view.put("content", result.content().length() > 400
                            ? result.content().substring(0, 400) + "…" : result.content());
                    return view;
                }).toList();
        return new ToolOutcome(false, json(Map.of("query", query, "results", views)), false);
    }

    private ToolOutcome generateReport(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        ExperimentReportDocument report = reportService.generate(jobId);
        session.setReportId(report.jobId());
        session.transition(AutonomousStatus.GENERATING_REPORT);
        return new ToolOutcome(false, "报告已生成（generatedBy=" + report.generatedBy()
                + "，结论 " + report.conclusions().size() + " 条，Citation " + report.citations().size() + " 条）", false);
    }

    /**
     * Hands the model the report metrics so it can analyse the simulation outcome; the
     * analysis text itself is delivered through finish and saved on the session.
     */
    private ToolOutcome analyzeResult(AutonomousSession session, String jobId) {
        if (jobId == null || jobId.isBlank()) jobId = session.jobId();
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("缺少 jobId");
        ExperimentReportDocument report = reportService.get(jobId);
        StringBuilder summary = new StringBuilder();
        summary.append("实验 ").append(jobId).append(" 仿真指标（分析时只能引用这些数据，不得编造）：\n");
        if (report.data() == null) {
            // Generic (declarative-template) report: analyse through the conclusions and
            // citations the interpreter grounded on the validated CSV.
            summary.append("- 结论（均有 Citation 支撑）：\n");
            for (ReportConclusion conclusion : report.conclusions()) {
                summary.append("  - ").append(conclusion.text())
                        .append("（metric=").append(conclusion.metricName())
                        .append("，value=").append(conclusion.metricValue())
                        .append("，citations=").append(conclusion.citationIds()).append("）\n");
            }
            summary.append("- Citation 总数：").append(report.citations().size()).append('\n');
            return new ToolOutcome(false, summary.toString(), false);
        }
        ExperimentReportData data = report.data();
        summary.append("- 参数点数：").append(data.totalPoints()).append('\n');
        summary.append("- 最小识别准确率：").append(data.accuracySummary().minAccuracy()).append('\n');
        summary.append("- 最大识别准确率：").append(data.accuracySummary().maxAccuracy()).append('\n');
        summary.append("- 平均识别准确率：").append(data.accuracySummary().meanAccuracy()).append('\n');
        summary.append("- 码长维度：").append(data.codeLengthTrends().stream()
                .map(t -> String.valueOf(t.codeLength())).toList()).append('\n');
        for (ReportConclusion conclusion : report.conclusions()) {
            summary.append("- 结论 ").append(conclusion.conclusionId()).append("：")
                    .append(conclusion.text()).append('\n');
        }
        return new ToolOutcome(false, summary.toString(), false);
    }

    private ToolOutcome finish(AutonomousSession session, Map<String, Object> arguments) {
        // Enforced at the session layer, not the prompt: an analysis is only accepted after
        // the model actually read the result metrics through analyzeResult.
        if (session.analyzeResults() && session.steps().stream()
                .noneMatch(step -> "analyzeResult".equals(step.toolName()))) {
            throw new IllegalArgumentException(
                    "用户要求结果分析：必须先调用 analyzeResult 读取仿真指标，再在 finish 中给出分析");
        }
        String message = text(arguments, "message");
        if (session.analyzeResults() && !message.isBlank()) {
            session.setAnalysis(message);
        }
        return new ToolOutcome(false, "FINISHED: " + message, true);
    }

    private ExperimentDefinition definitionOf(String templateId) {
        return templateCatalog.templateDetail(templateId).definition();
    }

    private ExperimentSpec parseSpec(String specJson) {
        try {
            return objectMapper.readValue(specJson, ExperimentSpec.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Spec JSON 无法解析：" + e.getMessage());
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) return "";
        // Structured arguments (e.g. a specJson object) are re-serialised to JSON instead
        // of being mangled by String.valueOf into "{experimentType=...}".
        if (value instanceof String string) return string;
        if (value instanceof Map || value instanceof List) return json(value);
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(String.valueOf(item));
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("工具结果序列化失败", e);
        }
    }

    public record ToolOutcome(boolean suspended, String result, boolean finished) { }
}
