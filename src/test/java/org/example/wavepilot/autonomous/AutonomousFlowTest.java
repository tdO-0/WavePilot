package org.example.wavepilot.autonomous;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.IntegrationTestSupport;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.report.ControlledReportAgent;
import org.example.wavepilot.report.ReportCitationValidator;
import org.example.wavepilot.report.ReportDataAssembler;
import org.example.wavepilot.report.ReportService;
import org.example.wavepilot.report.TemplateExperimentReportGenerator;
import org.example.wavepilot.runner.MatlabTemplateCatalog;
import org.example.wavepilot.runner.MockExperimentRunner;
import org.example.wavepilot.template.ApprovedTemplateDefinitionLoader;
import org.example.wavepilot.template.BuiltInTemplateProvider;
import org.example.wavepilot.template.FileSystemTemplateRepository;
import org.example.wavepilot.template.TemplateCatalogService;
import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.TemplateRegistry;
import org.example.wavepilot.template.TemplateRootProperties;
import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;
import org.example.wavepilot.template.generation.StubTemplateGenerationModel;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.example.wavepilot.template.publish.TemplatePublishingService;
import org.example.wavepilot.template.security.TemplateSecurityScanner;
import org.example.wavepilot.template.smoke.CandidateSmokeService;
import org.example.wavepilot.template.smoke.FakeCandidateSmokeRunner;
import org.example.wavepilot.template.validation.CandidateValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests of the controlled autonomous loop with a scripted stub model. */
class AutonomousFlowTest {

    @TempDir Path root;

    private record Stack(AutonomousSessionService autonomous,
                         AutonomousToolExecutor executor, ExperimentDefinitionParser parser,
                         CandidateTemplateRepository candidates, TemplateRegistry registry,
                         ExperimentDefinitionRegistry definitions,
                         TemplatePublishingService publishing,
                         ExperimentService experimentService,
                         org.example.wavepilot.replay.ReplayService replayService) { }

    private Stack stack(AutonomousModel model) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TemplateRootProperties props = new TemplateRootProperties(
                root.resolve("data/wavepilot/templates").toString());
        FileSystemTemplateRepository files = new FileSystemTemplateRepository(props, mapper);
        TemplateRegistry registry = new TemplateRegistry(files);
        ExperimentDefinitionRegistry definitions = new ExperimentDefinitionRegistry();
        CandidateTemplateRepository candidates = new CandidateTemplateRepository();
        CandidateStateMachine machine = new CandidateStateMachine();
        ExperimentDefinitionParser parser = new ExperimentDefinitionParser();
        ExperimentDefinitionValidator definitionValidator = new ExperimentDefinitionValidator();
        // Like production startup: restore declarative definitions of already-published
        // templates from disk (a fresh stack otherwise starts with an empty registry).
        new ApprovedTemplateDefinitionLoader(files, definitions, parser, definitionValidator)
                .loadPublishedDefinitions();
        // Like production startup: the built-in polar templates are always present, so
        // searchTemplates can match them and parameter collection uses their contract.
        for (TemplateRecord builtIn : new BuiltInTemplateProvider().builtInRecords()) {
            registry.registerApproved(builtIn);
        }
        TemplateGenerationService generation = new TemplateGenerationService(
                new StubTemplateGenerationModel(), candidates, machine, parser, definitionValidator,
                definitions, registry, mapper);
        CandidateValidationService validation = new CandidateValidationService(candidates,
                new TemplateSecurityScanner(), parser, definitionValidator, mapper);
        CandidateSmokeService smoke = new CandidateSmokeService(candidates, machine,
                new FakeCandidateSmokeRunner());
        TemplatePublishingService publishing = new TemplatePublishingService(candidates, machine,
                registry, files, definitions, parser, definitionValidator, mapper);
        TemplateCatalogService catalog = new TemplateCatalogService(registry, definitions, candidates);

        ExperimentSpecValidator specValidator = new ExperimentSpecValidator(definitions);
        ExperimentStateMachine stateMachine = new ExperimentStateMachine();
        InMemoryExperimentJobRepository jobs = new InMemoryExperimentJobRepository();
        ArtifactRegistry artifacts = new ArtifactRegistry(root.resolve("artifacts").toString(), mapper);
        // Wired like production: the same definitions registry reaches spec validation,
        // the mock runner's CSV contract and result validation.
        MockExperimentRunner runner = new MockExperimentRunner(artifacts, mapper, specValidator, definitions);
        ResultValidator resultValidator = new ResultValidator(mapper, specValidator, definitions);
        ExperimentService experimentService = new ExperimentService(specValidator, stateMachine,
                jobs, runner, artifacts, resultValidator, mapper, null, definitions);
        ReportDataAssembler assembler = new ReportDataAssembler(artifacts, mapper, definitions);
        ReportCitationValidator citationValidator = new ReportCitationValidator(artifacts, mapper);
        ReportService reportService = new ReportService(jobs, artifacts, assembler, citationValidator,
                new TemplateExperimentReportGenerator(), new ControlledReportAgent(), Optional.empty(),
                new org.example.wavepilot.report.DeclarativeResultInterpreter(artifacts, mapper, definitions),
                new org.example.wavepilot.report.GenericExperimentReportGenerator());

        // Replay / Eval / Knowledge services, wired like production so the goal tools read
        // real artifacts instead of pretending.
        var fingerprints = new org.example.wavepilot.evaluation.ReplayFingerprintService(mapper);
        org.example.wavepilot.replay.ReplayService replayService = new org.example.wavepilot.replay.ReplayService(
                experimentService, artifacts, fingerprints,
                new org.example.wavepilot.replay.ReplayComparisonEvaluator(
                        artifacts, mapper, fingerprints, definitions),
                new org.example.wavepilot.replay.MatlabTemplateDigest(),
                new org.example.wavepilot.replay.InMemoryReplayRepository(), mapper, 1.0e-9);
        org.example.wavepilot.knowledge.WavePilotEmbeddingService embedding =
                new IntegrationTestSupport.DeterministicEmbeddingService();
        var knowledgeRepository =
                new org.example.wavepilot.knowledge.repository.InMemoryWavePilotKnowledgeRepository(embedding);
        org.example.wavepilot.knowledge.KnowledgeService knowledgeService =
                new org.example.wavepilot.knowledge.KnowledgeService(
                        new org.example.wavepilot.knowledge.DocumentChunkService(),
                        new org.example.wavepilot.knowledge.KnowledgeIdFactory(), knowledgeRepository);
        var dataset = new org.example.wavepilot.evaluation.EvaluationDataset();
        var fixtureFactory = new org.example.wavepilot.evaluation.EvaluationFixtureFactory(jobs, artifacts, mapper);
        var modelRegistry = new org.example.wavepilot.evaluation.EvaluationModelRegistry(
                new org.example.wavepilot.evaluation.ReferenceStubModel(),
                new org.example.wavepilot.evaluation.RegressedStubModel(), Optional.empty());
        var metricCalculator = new org.example.wavepilot.evaluation.EvaluationMetricCalculator();
        var evaluationRepository = new org.example.wavepilot.evaluation.InMemoryEvaluationRepository();
        var toolGuard = new org.example.wavepilot.evaluation.EvaluationToolGuard();
        org.example.wavepilot.evaluation.EvaluationService evaluationService =
                new org.example.wavepilot.evaluation.EvaluationService(
                        modelRegistry, dataset, metricCalculator, artifacts, evaluationRepository,
                        new org.example.wavepilot.evaluation.executor.ModelDrivenCaseExecutor(specValidator, toolGuard),
                        new org.example.wavepilot.evaluation.executor.KnowledgeRetrievalExecutor(knowledgeRepository, dataset),
                        new org.example.wavepilot.evaluation.executor.JobCasesExecutor(experimentService, mapper, 10_000L),
                        new org.example.wavepilot.evaluation.executor.CitationGroundingExecutor(
                                fixtureFactory, artifacts, assembler, citationValidator),
                        new org.example.wavepilot.evaluation.executor.ReplayConsistencyExecutor(
                                experimentService, replayService, mapper, 10_000L));

        AutonomousToolExecutor executor = new AutonomousToolExecutor(catalog, generation, validation,
                smoke, experimentService, reportService, replayService, evaluationService,
                knowledgeService, parser, candidates, mapper);
        AutonomousSessionService autonomous = new AutonomousSessionService(executor, parser, definitions,
                candidates, publishing, mapper, emptyChatProvider(), model);
        return new Stack(autonomous, executor, parser, candidates, registry, definitions,
                publishing, experimentService, replayService);
    }

    private org.springframework.beans.factory.ObjectProvider<org.springframework.ai.chat.model.ChatModel> emptyChatProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public org.springframework.ai.chat.model.ChatModel getObject() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getObject(Object... args) { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return null; }
            @Override public void forEach(java.util.function.Consumer<? super org.springframework.ai.chat.model.ChatModel> action) { }
            @Override public java.util.stream.Stream<org.springframework.ai.chat.model.ChatModel> stream() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    @Test
    void noTemplateFlowGoesCandidateThenApprovalThenExperimentThenReport() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("新增一个 QPSK AWGN BER 仿真");

        // Wait for the first human point: candidate approval.
        AutonomousSession waitingApproval = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_APPROVAL);
        assertNotNull(waitingApproval.pendingCandidateId(), "候选审批挂起点必须携带候选");
        assertTrue(waitingApproval.steps().stream().anyMatch(s -> "smokeCandidate".equals(s.toolName())),
                "Smoke 必须已执行");
        assertTrue(waitingApproval.steps().stream().anyMatch(s -> "validateCandidate".equals(s.toolName())),
                "校验必须已执行");

        // Approve without an approver identity must be rejected.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> stack.autonomous().submitApproval(session.sessionId(), true, " "));

        // The session is mutable: capture the candidate id before approval clears it.
        String candidateId = waitingApproval.pendingCandidateId();
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");

        // Second human point: parameters.
        AutonomousSession waitingParams = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        Map<String, Object> pending = waitingParams.pendingParams();
        assertTrue(pending.containsKey("parameters"), "参数挂起点必须携带参数定义");
        assertTrue(pending.containsKey("candidateId"), "候选参数挂起点必须携带候选");

        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));

        // Terminal: the loop should finish with a report.
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());
        assertNotNull(done.jobId(), "必须有实验任务");
        assertNotNull(done.reportId(), "必须生成报告");
        assertTrue(done.steps().stream().anyMatch(s -> "waitForJobCompletion".equals(s.toolName())),
                "必须等待仿真结束");
        assertTrue(done.steps().stream().anyMatch(s -> "generateReport".equals(s.toolName())));
        assertEquals(TemplateCandidateStatus.ACTIVE,
                stack.candidates().findById(candidateId)
                        .map(TemplateCandidate::status).orElseThrow(),
                "候选必须已被批准发布");
    }

    @Test
    void withTemplateFlowSkipsCandidateAndGoesStraightToExperiment() throws Exception {
        // Phase 1: publish a demo template through the no-template flow (same filesystem root).
        // The request must mention QPSK so the scripted generator produces qpsk-awgn-ber.
        Stack prep = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession prepSession = prep.autonomous().start("准备 QPSK AWGN BER 模板");
        AutonomousSession prepApproval = awaitStatus(prepSession.sessionId(), prep, AutonomousStatus.WAITING_APPROVAL);
        prep.autonomous().submitApproval(prepSession.sessionId(), true, "test-user");
        AutonomousSession prepParams = awaitStatus(prepSession.sessionId(), prep, AutonomousStatus.WAITING_PARAMS);
        prep.autonomous().submitParams(prepSession.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        awaitTerminal(prepSession.sessionId(), prep);
        assertTrue(prep.registry().active("qpsk-awgn-ber").isPresent(), "准备阶段必须发布模板");

        // Phase 2: a fresh stack with the with-template strategy on the same registry.
        Stack use = stack(new AutonomousStubModel(true, "qpsk-awgn-ber",
                List.of("ebNoStart", "ebNoEnd", "ebNoStep", "frames")));
        AutonomousSession session = use.autonomous().start("QPSK AWGN BER 仿真");
        AutonomousSession waitingParams = awaitStatus(session.sessionId(), use, AutonomousStatus.WAITING_PARAMS);
        assertTrue(!waitingParams.pendingParams().get("templateId").toString().isBlank(),
                "有模板时必须按模板收集参数");
        use.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        AutonomousSession done = awaitTerminal(session.sessionId(), use);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());
        assertTrue(done.steps().stream().noneMatch(s -> "generateCandidate".equals(s.toolName())),
                "有可用模板时不得走候选流程");
        assertNotNull(done.jobId());
    }

    @Test
    void rejectingApprovalEndsTheSession() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("新增一个演示模板");
        AutonomousSession waiting = awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().submitApproval(session.sessionId(), false, "test-user");
        AutonomousSession done = stack.autonomous().get(session.sessionId());
        assertEquals(AutonomousStatus.FAILED, done.status());
        assertTrue(done.error().contains("拒绝"));
    }

    @Test
    void sessionSerializesToJsonWithRecordStyleAccessors() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AutonomousSession session = new AutonomousSession("QPSK 仿真", "stub-test");
        session.addStep("model", "模型思考内容", null, null, AutonomousStatus.UNDERSTANDING_INTENT);
        session.transition(AutonomousStatus.WAITING_PARAMS);
        String json = mapper.writeValueAsString(session);
        assertTrue(json.contains("\"sessionId\""), "session id must serialize: " + json);
        assertTrue(json.contains("\"status\":\"WAITING_PARAMS\""), "status must serialize");
        assertTrue(json.contains("\"steps\""), "timeline must serialize");
        assertTrue(json.contains("\"modelName\":\"stub-test\""), "model name must serialize");
        assertTrue(json.contains("模型思考内容"), "step content must serialize");
    }

    @Test
    void withoutAnApiKeyTheLoopFallsBackToTheStubModel() throws Exception {
        // The 7-arg constructor is used by Spring (dashScopeApiKey stays unset), and a
        // ChatModel provider exists: the key gate must still select the stub, not a real
        // model that would fail with invalid credentials.
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSessionService autonomous = new AutonomousSessionService(
                stack.executor(), stack.parser(), stack.definitions(), stack.candidates(),
                stack.publishing(), new ObjectMapper().findAndRegisterModules(),
                nonEmptyChatProvider());
        AutonomousSession session = autonomous.start("QPSK 仿真");
        assertEquals("stub-autonomous-no-templates", session.modelName(),
                "without a configured API key the loop must use the scripted stub");
        autonomous.cancel(session.sessionId());
    }

    private org.springframework.beans.factory.ObjectProvider<org.springframework.ai.chat.model.ChatModel> nonEmptyChatProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public org.springframework.ai.chat.model.ChatModel getObject() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getObject(Object... args) { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable() {
                return new org.springframework.ai.chat.model.ChatModel() {
                    @Override public org.springframework.ai.chat.model.ChatResponse call(
                            org.springframework.ai.chat.prompt.Prompt prompt) {
                        throw new AssertionError("the stub must be used when no API key is configured");
                    }
                    @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() { return null; }
                };
            }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return null; }
            @Override public void forEach(java.util.function.Consumer<? super org.springframework.ai.chat.model.ChatModel> action) { }
            @Override public java.util.stream.Stream<org.springframework.ai.chat.model.ChatModel> stream() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    @Test
    void builtInExperimentTypeGetsThePlatformParameterContractAndRunsTheUserValues()
            throws Exception {
        Stack stack = stack(new AutonomousModel() {
            private int calls = 0;
            @Override public String name() { return "stub-builtin-polar"; }
            @Override public String respond(List<String> history) {
                String joined = String.join("\n", history);
                if (!joined.contains("工具结果(searchTemplates)")) {
                    return "{\"tool\":\"searchTemplates\",\"arguments\":{\"query\":\"极化码\"}}";
                }
                if (!joined.contains("已挂起等待用户填写参数")) {
                    return "{\"tool\":\"requestParameterInput\",\"arguments\":{\"templateId\":\""
                            + MatlabTemplateCatalog.SIMPLE_TEMPLATE + "\"}}";
                }
                if (!joined.contains("工具结果(submitSpec)")) {
                    return "{\"tool\":\"submitSpec\",\"arguments\":{\"specJson\":\"\"}}";
                }
                if (!joined.contains("已成功")) {
                    return "{\"tool\":\"waitForJobCompletion\",\"arguments\":{\"jobId\":\""
                            + extractJob(joined) + "\"}}";
                }
                if (!joined.contains("报告已生成")) {
                    return "{\"tool\":\"generateReport\",\"arguments\":{\"jobId\":\""
                            + extractJob(joined) + "\"}}";
                }
                return "{\"tool\":\"finish\",\"arguments\":{\"message\":\"done\"}}";
            }
            private String extractJob(String joined) {
                int start = joined.indexOf("实验已提交：JOB-");
                return start < 0 ? "JOB-NONE"
                        : joined.substring(start + 6, joined.indexOf('，', start));
            }
        });
        AutonomousSession session = stack.autonomous().start("跑一个基础的极化码 K 识别仿真");
        AutonomousSession waiting = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        List<?> parameters = (List<?>) waiting.pendingParams().get("parameters");
        assertNotNull(parameters, "内置类型也必须给出参数定义");
        assertTrue(parameters.stream().map(String::valueOf).anyMatch(p -> p.contains("codeLengths")),
                "内置参数清单必须包含 codeLengths");
        assertTrue(parameters.stream().map(String::valueOf).anyMatch(p -> p.contains("errorRateEnd")),
                "内置参数清单必须包含错误率范围");

        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "codeLengths", "32,64", "errorRateStart", 0, "errorRateEnd", 0.2,
                "errorRateStep", 0.1, "sampleCount", 30, "monteCarloTimes", 5));
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status(), "会话错误：" + done.error());
        assertNotNull(done.jobId(), "内置类型也必须产出实验任务");
        assertEquals(List.of(32, 64), stack.experimentService().get(done.jobId()).getSpec().codeLengths(),
                "用户填写的码长必须进入 Spec");
        assertEquals(0.2, stack.experimentService().get(done.jobId()).getSpec().errorRateEnd(),
                "用户填写的错误率范围必须进入 Spec");
    }

    @Test
    void analyzeResultsRunsAfterTheReportAndSavesTheAnalysis() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of(), true));
        AutonomousSession session = stack.autonomous().start("QPSK 仿真，跑完后分析结果", true);
        AutonomousSession waitingApproval = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");
        AutonomousSession waitingParams = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status(), "会话错误：" + done.error());
        assertNotNull(done.analysis(), "开启结果分析时必须保存分析文本");
        assertTrue(done.analysis().startsWith("分析："), "分析文本必须来自模型："
                + done.analysis());
        assertTrue(done.steps().stream().anyMatch(s -> "analyzeResult".equals(s.toolName())),
                "必须调用 analyzeResult 读取指标数据");
    }

    @Test
    void finishWithoutAnalyzeResultIsRejectedAtTheSessionLayer() throws Exception {
        // A model that always finishes without reading the metrics must never succeed:
        // the session layer rejects finish until analyzeResult ran, and repeated refusal
        // terminates the session instead of accepting an ungrounded analysis.
        Stack stack = stack(new AutonomousModel() {
            @Override public String name() { return "stub-always-finish"; }
            @Override public String respond(List<String> history) {
                return "{\"tool\":\"finish\",\"arguments\":{\"message\":\"跳过了分析\"}}";
            }
        });
        AutonomousSession session = stack.autonomous().start("QPSK 仿真", true);
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.FAILED, done.status());
        assertNotNull(done.error());
        assertTrue(done.error().contains("必须先调用 analyzeResult"),
                "会话必须因拒绝未落地分析而终止：" + done.error());
        assertTrue(done.analysis() == null || done.analysis().isBlank(),
                "未读取指标的分析不得保存");
    }

    @Test
    void withoutAnalyzeResultsNoAnalysisIsProduced() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("QPSK 仿真");
        AutonomousSession waitingApproval = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");
        AutonomousSession waitingParams = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());
        assertTrue(done.analysis() == null || done.analysis().isBlank(),
                "未开启结果分析时不应产生分析文本");
    }

    @Test
    void aHandRolledSpecJsonIsRejectedAndOnlyTheSessionSpecIsSubmitted() throws Exception {
        Stack stack = stack(new AutonomousModel() {
            @Override public String name() { return "stub-handrolled-spec"; }
            @Override public String respond(List<String> history) {
                String joined = String.join("\n", history);
                if (!joined.contains("工具结果(searchTemplates)")) {
                    return "{\"tool\":\"searchTemplates\",\"arguments\":{\"query\":\"极化码\"}}";
                }
                if (!joined.contains("已挂起等待用户填写参数")) {
                    return "{\"tool\":\"requestParameterInput\",\"arguments\":{\"templateId\":\""
                            + MatlabTemplateCatalog.SIMPLE_TEMPLATE + "\"}}";
                }
                // First submission attempt invents a spec with an absurd code length;
                // it must be rejected even though the JSON is well formed.
                if (!joined.contains("没有可提交的 Spec") && !joined.contains("实验已提交")) {
                    return "{\"tool\":\"submitSpec\",\"arguments\":{\"specJson\":"
                            + "{\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\","
                            + "\"codeLengths\":[999],\"errorRateStart\":0.0,\"errorRateEnd\":0.1,"
                            + "\"errorRateStep\":0.05,\"sampleCount\":20,\"monteCarloTimes\":10,"
                            + "\"randomSeed\":20,\"outputTypes\":[\"ACCURACY_CSV\",\"RUN_LOG\"]}}}";
                }
                if (!joined.contains("实验已提交")) {
                    return "{\"tool\":\"submitSpec\",\"arguments\":{}}";
                }
                if (!joined.contains("已成功")) {
                    return "{\"tool\":\"waitForJobCompletion\",\"arguments\":{\"jobId\":\"\"}}";
                }
                if (!joined.contains("报告已生成")) {
                    return "{\"tool\":\"generateReport\",\"arguments\":{\"jobId\":\"\"}}";
                }
                return "{\"tool\":\"finish\",\"arguments\":{\"message\":\"done\"}}";
            }
        });
        AutonomousSession session = stack.autonomous().start("极化码仿真");
        AutonomousSession waiting = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "codeLengths", "32,64", "errorRateStart", 0, "errorRateEnd", 0.2,
                "errorRateStep", 0.1, "sampleCount", 30, "monteCarloTimes", 5));
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status(), "会话错误：" + done.error());
        assertTrue(done.steps().stream().anyMatch(s -> s.toolResult() != null
                        && s.toolResult().contains("已忽略模型传入的 specJson")),
                "模型手写的 specJson 必须被忽略并在时间线记录");
        assertEquals(List.of(32, 64),
                stack.experimentService().get(done.jobId()).getSpec().codeLengths(),
                "实验必须使用用户填写的参数，而不是模型编造的 [999]");
    }

    @Test
    void parameterNamesIsAcceptedAsAnAliasForKeyParameters() throws Exception {
        Stack stack = stack(new AutonomousModel() {
            @Override public String name() { return "stub-parameternames"; }
            @Override public String respond(List<String> history) {
                if (!String.join("\n", history).contains("工具结果(searchTemplates)")) {
                    return "{\"tool\":\"searchTemplates\",\"arguments\":{\"query\":\"极化码\"}}";
                }
                return "{\"tool\":\"requestParameterInput\",\"arguments\":{\"templateId\":\""
                        + MatlabTemplateCatalog.SIMPLE_TEMPLATE + "\","
                        + "\"parameterNames\":[\"codeLengths\",\"errorRateEnd\"]}}";
            }
        });
        AutonomousSession session = stack.autonomous().start("极化码仿真");
        AutonomousSession waiting = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        List<?> parameters = (List<?>) waiting.pendingParams().get("parameters");
        assertNotNull(parameters);
        assertTrue(parameters.stream().map(String::valueOf).anyMatch(p -> p.contains("codeLengths")),
                "parameterNames 键下的参数名必须被采纳");
        assertTrue(parameters.stream().map(String::valueOf).anyMatch(p -> p.contains("errorRateEnd")),
                "parameterNames 键下的参数名必须被采纳（errorRateEnd）");
        stack.autonomous().cancel(session.sessionId());
    }

    @Test
    void aFailedToolCallIsFedBackToTheModelInsteadOfKillingTheSession() throws Exception {
        Stack stack = stack(new AutonomousModel() {
            private int calls = 0;
            @Override public String name() { return "stub-retry-once"; }
            @Override public String respond(List<String> history) {
                // First round: a tool call that fails (unknown candidate). Second round:
                // finish. The loop must feed the failure back and let the model retry.
                if (calls++ == 0) {
                    return "{\"tool\":\"validateCandidate\",\"arguments\":{\"candidateId\":\"CAND-NOT-EXIST\"}}";
                }
                return "{\"tool\":\"finish\",\"arguments\":{\"message\":\"done\"}}";
            }
        });
        AutonomousSession session = stack.autonomous().start("重试演示");
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status(), "the loop must survive one failed tool call");
        assertTrue(done.steps().stream().anyMatch(s -> "tool".equals(s.role())
                        && s.toolResult() != null && s.toolResult().contains("工具执行失败")),
                "the failed call must be recorded on the timeline");
    }

    @Test
    void cancelStopsTheSession() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("新增一个演示模板");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().cancel(session.sessionId());
        assertEquals(AutonomousStatus.CANCELLED, stack.autonomous().get(session.sessionId()).status());
    }

    private AutonomousSession awaitStatus(String sessionId, Stack stack, AutonomousStatus target)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            AutonomousSession session = stack.autonomous().get(sessionId);
            if (session.status() == target) return session;
            if (session.status() == AutonomousStatus.FAILED
                    || session.status() == AutonomousStatus.CANCELLED
                    || session.status() == AutonomousStatus.BLOCKED) {
                throw new AssertionError("会话异常终止：" + session.status() + " " + session.error());
            }
            Thread.sleep(50);
        }
        AutonomousSession current = stack.autonomous().get(sessionId);
        String tail = current.steps().stream().map(s -> (s.role() + ":" + (s.toolName() == null ? "" : s.toolName())
                + ":" + (s.toolResult() == null ? "" : s.toolResult()).substring(0,
                        Math.min(80, (s.toolResult() == null ? "" : s.toolResult()).length()))))
                .reduce((a, b) -> b).orElse("(无步骤)");
        throw new AssertionError("等待 " + target + " 超时；当前状态 " + current.status()
                + "；最后步骤：" + tail);
    }

    private AutonomousSession awaitTerminal(String sessionId, Stack stack) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            AutonomousSession session = stack.autonomous().get(sessionId);
            if (session.status() == AutonomousStatus.SUCCEEDED
                    || session.status() == AutonomousStatus.FAILED
                    || session.status() == AutonomousStatus.CANCELLED) {
                if (session.status() == AutonomousStatus.FAILED && session.error() != null) {
                    String tail = session.steps().stream()
                            .map(s -> (s.toolName() == null ? s.role() : s.toolName()) + ":"
                                    + (s.toolResult() == null ? "" : s.toolResult()))
                            .reduce((a, b) -> b).orElse("(无步骤)");
                    System.out.println("DEBUG-FAIL session=" + sessionId + " error=" + session.error()
                            + " tail=" + tail);
                }
                return session;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待终态超时");
    }
}
