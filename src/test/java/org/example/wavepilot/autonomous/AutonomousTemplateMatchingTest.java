package org.example.wavepilot.autonomous;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.example.wavepilot.template.BuiltInTemplateProvider;
import org.example.wavepilot.template.FileSystemTemplateRepository;
import org.example.wavepilot.template.TemplateCatalogService;
import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.TemplateRegistry;
import org.example.wavepilot.template.TemplateRootProperties;
import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent must match templates to the user request instead of reusing a fixed one:
 * searchTemplates filters by query and reports matches/versions, and requesting
 * parameters without any template context is guided towards creating a candidate.
 */
class AutonomousTemplateMatchingTest {

    @TempDir
    Path root;

    private AutonomousToolExecutor executor() throws Exception {
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
        MockExperimentRunner runner = new MockExperimentRunner(artifacts, mapper, specValidator, definitions);
        ResultValidator resultValidator = new ResultValidator(mapper, specValidator, definitions);
        ExperimentService experimentService = new ExperimentService(specValidator, stateMachine,
                jobs, runner, artifacts, resultValidator, mapper);
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
        var embedding = new org.example.wavepilot.IntegrationTestSupport.DeterministicEmbeddingService();
        var knowledgeRepository =
                new org.example.wavepilot.knowledge.repository.InMemoryWavePilotKnowledgeRepository(embedding);
        var knowledgeService = new org.example.wavepilot.knowledge.KnowledgeService(
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
        var evaluationService = new org.example.wavepilot.evaluation.EvaluationService(
                modelRegistry, dataset, metricCalculator, artifacts, evaluationRepository,
                new org.example.wavepilot.evaluation.executor.ModelDrivenCaseExecutor(specValidator, toolGuard),
                new org.example.wavepilot.evaluation.executor.KnowledgeRetrievalExecutor(knowledgeRepository, dataset),
                new org.example.wavepilot.evaluation.executor.JobCasesExecutor(experimentService, mapper, 10_000L),
                new org.example.wavepilot.evaluation.executor.CitationGroundingExecutor(
                        fixtureFactory, artifacts, assembler, citationValidator),
                new org.example.wavepilot.evaluation.executor.ReplayConsistencyExecutor(
                        experimentService, replayService, mapper, 10_000L));
        return new AutonomousToolExecutor(catalog, generation, validation, smoke,
                experimentService, reportService, replayService, evaluationService,
                knowledgeService, parser, candidates, mapper);
    }

    @Test
    void searchTemplatesMatchesPolarQueryToTheBuiltInPolarTemplate() throws Exception {
        AutonomousToolExecutor executor = executor();
        AutonomousSession session = new AutonomousSession("跑一个极化码 K 识别仿真", "stub");
        AutonomousToolExecutor.ToolOutcome outcome = executor.execute(session, "searchTemplates", Map.of("query", "极化码"));
        assertFalse(outcome.suspended(), "search is a read-only tool");
        assertTrue(outcome.result().contains("hasMatch"));
        assertTrue(outcome.result().contains("polar-k-identification-simple-v1"),
                "a polar query must match the built-in polar template");
        assertTrue(outcome.result().contains("usable"),
                "matching templates must carry usability/version metadata");
    }

    @Test
    void searchTemplatesWithEmptyQueryListsUsableTemplates() throws Exception {
        AutonomousToolExecutor executor = executor();
        AutonomousSession session = new AutonomousSession("BPSK AWGN BER", "stub");
        AutonomousToolExecutor.ToolOutcome outcome = executor.execute(session, "searchTemplates", Map.of("query", ""));
        assertTrue(outcome.result().contains("polar-k-identification-simple-v1"),
                "an empty query is a directory browse and must list ACTIVE templates");
        assertTrue(outcome.result().contains("usable"),
                "directory browse must still carry usability metadata");
    }

    @Test
    void searchTemplatesDoesNotMatchAnUnrelatedQuery() throws Exception {
        AutonomousToolExecutor executor = executor();
        AutonomousSession session = new AutonomousSession("帮我新建一个 16QAM 模板", "stub");
        AutonomousToolExecutor.ToolOutcome outcome = executor.execute(session, "searchTemplates", Map.of("query", "16QAM"));
        assertTrue(outcome.result().contains("\"hasMatch\" : false")
                        || outcome.result().contains("\"hasMatch\":false"),
                "no matching template must be reported as no match: " + outcome.result());
    }

    @Test
    void parameterRequestWithoutAnyTemplateContextIsGuidedNotFixed() throws Exception {
        AutonomousToolExecutor executor = executor();
        AutonomousSession session = new AutonomousSession("跑一个 QPSK BER 实验", "stub");
        AutonomousToolExecutor.ToolOutcome outcome = executor.execute(session, "requestParameterInput", Map.of());
        assertFalse(outcome.suspended(),
                "without a template context the loop must not park on a fixed dialog");
        assertTrue(outcome.result().contains("没有可用的模板上下文"),
                "the model must be guided to match or create a template: " + outcome.result());
        assertEquals(AutonomousStatus.UNDERSTANDING_INTENT, session.status(),
                "the session must not move to WAITING_PARAMS without a template");
    }

    @Test
    void parameterRequestWithTemplateIdCarriesVersionAndDefinitions() throws Exception {
        AutonomousToolExecutor executor = executor();
        AutonomousSession session = new AutonomousSession("跑一个极化码 K 识别仿真", "stub");
        AutonomousToolExecutor.ToolOutcome outcome = executor.execute(session, "requestParameterInput",
                Map.of("templateId", "polar-k-identification-simple-v1"));
        assertTrue(outcome.suspended(), "with a template the loop must park for parameters");
        Map<String, Object> pending = session.pendingParams();
        assertEquals("polar-k-identification-simple-v1", pending.get("templateId"));
        assertTrue(String.valueOf(pending.get("templateDisplayName")).contains("极化码"),
                "the pending state must carry the matched template name");
        assertTrue(List.class.isInstance(pending.get("parameters")),
                "the pending state must carry the template's real parameters");
        assertTrue(pending.get("parameters").toString().contains("codeLengths"),
                "polar parameters must come from the matched template contract");
    }
}
