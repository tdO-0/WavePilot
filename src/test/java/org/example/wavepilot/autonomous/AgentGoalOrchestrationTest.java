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
import org.example.wavepilot.runner.MockExperimentRunner;
import org.example.wavepilot.template.ApprovedTemplateDefinitionLoader;
import org.example.wavepilot.template.FileSystemTemplateRepository;
import org.example.wavepilot.template.TemplateCatalogService;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Agent Goal orchestration: one user goal drives template lookup, candidate
 * creation/validation/smoke, human approval, publication, parameter collection, experiment
 * run, report and replay — with every step behind the whitelisted tool executor.
 */
class AgentGoalOrchestrationTest {

    @TempDir Path root;

    private record Stack(AutonomousSessionService autonomous,
                         AutonomousToolExecutor executor, ExperimentDefinitionParser parser,
                         CandidateTemplateRepository candidates, TemplateRegistry registry,
                         ExperimentDefinitionRegistry definitions,
                         TemplatePublishingService publishing,
                         ExperimentService experimentService,
                         org.example.wavepilot.replay.ReplayService replayService,
                         org.example.wavepilot.report.ReportService reportService) { }

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
        new ApprovedTemplateDefinitionLoader(files, definitions, parser, definitionValidator)
                .loadPublishedDefinitions();
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
                publishing, experimentService, replayService, reportService);
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

    /* Case 1 + Case 2: template list query and experiment on an existing template are
       read-only until the user states a runnable goal. The template is published first
       through the full candidate chain so the flow can find it. */
    @Test
    void case1And2_existingTemplateFlowRunsExperimentAndStopsWithoutWrites() throws Exception {
        // Phase 1: publish a demo template through the candidate chain (same root).
        Stack prep = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession prepSession = prep.autonomous().start("准备 QPSK AWGN BER 模板");
        awaitStatus(prepSession.sessionId(), prep, AutonomousStatus.WAITING_APPROVAL);
        prep.autonomous().submitApproval(prepSession.sessionId(), true, "test-user");
        awaitStatus(prepSession.sessionId(), prep, AutonomousStatus.WAITING_PARAMS);
        prep.autonomous().submitParams(prepSession.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        awaitTerminal(prepSession.sessionId(), prep);

        // Phase 2: the goal flow finds the ACTIVE template and runs without fabricating a candidate.
        Stack stack = stack(new AutonomousStubModel(true, "qpsk-awgn-ber", List.of()));
        AutonomousSession session = stack.autonomous().start("用已有的 QPSK 模板跑 BER 实验");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));

        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());
        // Read-only first: template lookup happened, no candidate was fabricated.
        assertTrue(done.steps().stream().anyMatch(s -> "searchTemplates".equals(s.toolName())),
                "must query templates first");
        assertFalse(done.steps().stream().anyMatch(s -> "generateCandidate".equals(s.toolName())),
                "existing template flow must not generate candidates");
        assertNotNull(done.jobId(), "must create the experiment job");
        assertNotNull(done.reportId(), "must generate the report");
    }

    /* Case 3: no matching template -> generate -> validate -> smoke -> STOP at approval. */
    @Test
    void case3_noTemplateStopsAtHumanApproval() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("跑一个 QPSK AWGN BER 实验");

        AutonomousSession waiting = awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        assertNotNull(waiting.pendingCandidateId(), "must park at candidate approval");
        assertTrue(waiting.steps().stream().anyMatch(s -> "generateCandidate".equals(s.toolName())));
        assertTrue(waiting.steps().stream().anyMatch(s -> "validateCandidate".equals(s.toolName())));
        assertTrue(waiting.steps().stream().anyMatch(s -> "smokeCandidate".equals(s.toolName())));
        assertTrue(waiting.pendingParams().containsKey("securityFindings"),
                "approval park must carry the security report");
        assertTrue(waiting.pendingParams().containsKey("smokeReport"),
                "approval park must carry the smoke report");
        assertEquals(null, waiting.jobId(), "no experiment may run before approval");
    }

    /* Case 4: user approves -> publish ACTIVE -> resume the original goal -> experiment ->
       result. The original request is the same goal, and the candidate becomes ACTIVE. */
    @Test
    void case4_approvalPublishesAndResumesTheOriginalGoal() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("跑一个 QPSK AWGN BER 实验");

        AutonomousSession waiting = awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        String candidateId = waiting.pendingCandidateId();
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");

        AutonomousSession waitingParams = awaitStatus(session.sessionId(), stack,
                AutonomousStatus.WAITING_PARAMS);
        assertEquals("qpsk-awgn-ber", waitingParams.pendingParams().get("templateId"),
                "after approval the goal resumes with the just-published template");
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));

        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());
        assertEquals(TemplateCandidateStatus.ACTIVE,
                stack.candidates().findById(candidateId).map(TemplateCandidate::status).orElseThrow(),
                "the candidate must be published ACTIVE");
        assertNotNull(done.jobId(), "the resumed goal must create the experiment");
    }

    /* Case 5: bounded retries — a model that keeps attempting an unauthorized tool is
       stopped by the session guardrails instead of looping forever. */
    @Test
    void case5_boundedRetriesStopARogueModel() throws Exception {
        Stack stack = stack(new UnauthorizedLoopModel());
        AutonomousSession session = stack.autonomous().start("跑一个 QPSK 实验");

        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.FAILED, done.status());
        assertTrue(done.error() != null && done.error().contains("越权"),
                "the session must stop with a readable guardrail message");
    }

    /* Case 6: missing parameters are answered inline in the chat (rawText) and the goal
       continues; the loop then collects the exact values and runs the experiment. */
    @Test
    void case6_missingParametersContinueViaChatAnswer() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("跑一个 QPSK BER 实验");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");

        // First human point after approval: parameters. Answer in natural language.
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of("rawText", "0-10 dB，100000 帧"));

        // The stub re-requests exact values after a raw answer; the user fills them in.
        AutonomousSession again = awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_PARAMS);
        assertTrue(again.pendingParams().containsKey("parameters"),
                "rawText answer must keep the parameter contract collectable");
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 10, "ebNoStep", 1, "frames", 100000));

        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());
        assertNotNull(done.jobId());
    }

    /* Case 7: report and citation tools expose provenance from the real report chain. */
    @Test
    void case7_reportAndCitationsAreReadable() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("跑一个 QPSK BER 实验");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());

        String citations = stack.executor().execute(done, "getCitations",
                Map.of("jobId", done.jobId())).result();
        assertTrue(citations.contains("citations"), "getCitations must return the citation list");
        assertTrue(citations.contains("sha256") || citations.contains("verified"),
                "citations must carry provenance");
    }

    /* Case 8: replay tools create an independent run and report a comparison verdict. */
    @Test
    void case8_replayAndComparisonTools() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        AutonomousSession session = stack.autonomous().start("跑一个 QPSK BER 实验");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        stack.autonomous().submitApproval(session.sessionId(), true, "test-user");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_PARAMS);
        stack.autonomous().submitParams(session.sessionId(), Map.of(
                "ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 0.5, "frames", 200));
        AutonomousSession done = awaitTerminal(session.sessionId(), stack);
        assertEquals(AutonomousStatus.SUCCEEDED, done.status());

        String created = stack.executor().execute(done, "createReplay",
                Map.of("jobId", done.jobId())).result();
        assertTrue(created.contains("Replay 已创建"), "createReplay must return the replay id");

        // Replay runs asynchronously; wait until the comparison is ready.
        String replayId = awaitReplayComparison(stack);
        String comparison = stack.executor().execute(done, "getReplayComparison",
                Map.of("replayId", replayId)).result();
        assertTrue(comparison.contains("verdict") && comparison.contains("consistent"),
                "comparison must expose the verdict");
    }

    /** Replay is async; poll until its comparison result is computed (bounded). */
    private String awaitReplayComparison(Stack stack) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var replays = stack.replayService().list();
            if (!replays.isEmpty()) {
                String replayId = replays.get(0).getReplayId();
                try {
                    stack.replayService().comparison(replayId);
                    return replayId;
                } catch (java.util.NoSuchElementException notReady) {
                    var record = replays.get(0);
                    if (record.getFailureReason() != null) {
                        throw new AssertionError("Replay failed: " + record.getFailureReason());
                    }
                    // still running
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        var record = stack.replayService().list().get(0);
        throw new AssertionError("Replay comparison never became ready; status="
                + record.getStatus() + " failure=" + record.getFailureReason());
    }

    /* Case 9: a candidate that requires a custom extension can never be published. */
    @Test
    void case9_customExtensionCandidateCannotBePublished() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        TemplateCandidate requiresCustom = new TemplateCandidate(
                "CAND-REQUIRES-1", "complex-ext", "complex-ext", "复杂模板", "1.0.0",
                TemplateCandidateStatus.REQUIRES_CUSTOM_EXTENSION,
                org.example.wavepilot.template.TemplateSource.AGENT_GENERATED,
                "复杂规则模板", "templateId: complex-ext", "{}", "需要专用扩展",
                List.of(), List.of(), List.of(), List.of(), null, false,
                "REQUIRES_CUSTOM_EXTENSION: 声明式系统无法表达该模板的复杂规则",
                Instant.now(), Instant.now());
        stack.candidates().save(requiresCustom);

        assertThrows(IllegalStateException.class,
                () -> stack.publishing().approveAndPublish("CAND-REQUIRES-1", "test-user"),
                "a REQUIRES_CUSTOM_EXTENSION candidate must never be published");
    }

    /* Case 10: the agent can never approve its own template — no approve/publish tool
       exists in the whitelist, and approval always requires an explicit approver. */
    @Test
    void case10_agentCannotSelfApprove() throws Exception {
        Stack stack = stack(new AutonomousStubModel(false, "", List.of()));
        assertFalse(AutonomousToolExecutor.WHITELIST.contains("approve"),
                "no approve tool may exist for the agent");
        assertFalse(AutonomousToolExecutor.WHITELIST.contains("publish"),
                "no publish tool may exist for the agent");

        AutonomousSession session = stack.autonomous().start("跑一个 QPSK BER 实验");
        awaitStatus(session.sessionId(), stack, AutonomousStatus.WAITING_APPROVAL);
        assertThrows(IllegalArgumentException.class,
                () -> stack.autonomous().submitApproval(session.sessionId(), true, " "),
                "approval without an approver identity must be rejected");
    }

    /** A model that keeps calling an unauthorized tool until the guardrails stop it. */
    private static final class UnauthorizedLoopModel implements AutonomousModel {
        @Override public String name() { return "rogue-model"; }
        @Override public String respond(List<String> history) {
            return "{\"tool\":\"approveTemplate\",\"arguments\":{}}";
        }
    }

    /* ---------------- helpers ---------------- */

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
        throw new AssertionError("等待 " + target + " 超时；当前 "
                + stack.autonomous().get(sessionId).status());
    }

    private AutonomousSession awaitTerminal(String sessionId, Stack stack) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            AutonomousSession session = stack.autonomous().get(sessionId);
            if (session.status() == AutonomousStatus.SUCCEEDED
                    || session.status() == AutonomousStatus.FAILED
                    || session.status() == AutonomousStatus.CANCELLED) {
                return session;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("等待终态超时；当前 " + stack.autonomous().get(sessionId).status());
    }
}
