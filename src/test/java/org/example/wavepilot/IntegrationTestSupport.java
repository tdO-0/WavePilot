package org.example.wavepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.evaluation.EvaluationDataset;
import org.example.wavepilot.evaluation.EvaluationFixtureFactory;
import org.example.wavepilot.evaluation.EvaluationMetricCalculator;
import org.example.wavepilot.evaluation.EvaluationModelRegistry;
import org.example.wavepilot.evaluation.EvaluationService;
import org.example.wavepilot.evaluation.EvaluationToolGuard;
import org.example.wavepilot.evaluation.InMemoryEvaluationRepository;
import org.example.wavepilot.evaluation.ReferenceStubModel;
import org.example.wavepilot.evaluation.RegressedStubModel;
import org.example.wavepilot.evaluation.executor.CitationGroundingExecutor;
import org.example.wavepilot.evaluation.executor.JobCasesExecutor;
import org.example.wavepilot.evaluation.executor.KnowledgeRetrievalExecutor;
import org.example.wavepilot.evaluation.executor.ModelDrivenCaseExecutor;
import org.example.wavepilot.evaluation.executor.ReplayConsistencyExecutor;
import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.knowledge.WavePilotEmbeddingService;
import org.example.wavepilot.knowledge.repository.InMemoryWavePilotKnowledgeRepository;
import org.example.wavepilot.replay.InMemoryReplayRepository;
import org.example.wavepilot.replay.MatlabTemplateDigest;
import org.example.wavepilot.replay.ReplayComparisonEvaluator;
import org.example.wavepilot.replay.ReplayService;
import org.example.wavepilot.report.ControlledReportAgent;
import org.example.wavepilot.report.ReportCitationValidator;
import org.example.wavepilot.report.ReportDataAssembler;
import org.example.wavepilot.report.ReportService;
import org.example.wavepilot.report.TemplateExperimentReportGenerator;
import org.example.wavepilot.replay.ReplayTestSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Full offline integration stack: deterministic 13-column runner (report/replay compatible),
 * memory knowledge repository with deterministic embedding, and every production service
 * wired the way the Spring context wires them. No Milvus, MATLAB or DashScope.
 */
public final class IntegrationTestSupport {

    private static final Pattern TERM = Pattern.compile("[\\p{IsHan}]|[A-Za-z0-9_]+");

    private IntegrationTestSupport() { }

    public static Stack stack(Path root) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(root.toString(), mapper);
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator();
        ExperimentStateMachine stateMachine = new ExperimentStateMachine();
        InMemoryExperimentJobRepository jobRepository = new InMemoryExperimentJobRepository();
        ReplayTestSupport.DeterministicPolarRunner runner =
                new ReplayTestSupport.DeterministicPolarRunner(registry, mapper, specValidator);
        ResultValidator resultValidator = new ResultValidator(mapper, specValidator);
        ExperimentService experimentService = new ExperimentService(specValidator, stateMachine,
                jobRepository, runner, registry, resultValidator, mapper);

        var fingerprints = new org.example.wavepilot.evaluation.ReplayFingerprintService(mapper);
        MatlabTemplateDigest digest = new MatlabTemplateDigest();
        ReplayComparisonEvaluator replayEvaluator = new ReplayComparisonEvaluator(registry, mapper, fingerprints);
        InMemoryReplayRepository replayRepository = new InMemoryReplayRepository();
        ReplayService replayService = new ReplayService(experimentService, registry, fingerprints,
                replayEvaluator, digest, replayRepository, mapper, 1.0e-9);

        ReportDataAssembler assembler = new ReportDataAssembler(registry, mapper);
        ReportCitationValidator citationValidator = new ReportCitationValidator(registry, mapper);
        TemplateExperimentReportGenerator templateGenerator = new TemplateExperimentReportGenerator();
        ControlledReportAgent reportAgent = new ControlledReportAgent();
        ReportService reportService = new ReportService(jobRepository, registry, assembler,
                citationValidator, templateGenerator, reportAgent, Optional.empty());

        DeterministicEmbeddingService embedding = new DeterministicEmbeddingService();
        InMemoryWavePilotKnowledgeRepository knowledgeRepository =
                new InMemoryWavePilotKnowledgeRepository(embedding);
        EvaluationDataset dataset = new EvaluationDataset();
        EvaluationFixtureFactory fixtureFactory =
                new EvaluationFixtureFactory(jobRepository, registry, mapper);
        EvaluationModelRegistry modelRegistry = new EvaluationModelRegistry(
                new ReferenceStubModel(), new RegressedStubModel(), java.util.Optional.empty());
        EvaluationMetricCalculator metricCalculator = new EvaluationMetricCalculator();
        InMemoryEvaluationRepository evaluationRepository = new InMemoryEvaluationRepository();
        EvaluationToolGuard toolGuard = new EvaluationToolGuard();
        ModelDrivenCaseExecutor modelExecutor = new ModelDrivenCaseExecutor(specValidator, toolGuard);
        KnowledgeRetrievalExecutor knowledgeExecutor = new KnowledgeRetrievalExecutor(knowledgeRepository, dataset);
        JobCasesExecutor jobExecutor = new JobCasesExecutor(experimentService, mapper, 10_000L);
        CitationGroundingExecutor citationExecutor =
                new CitationGroundingExecutor(fixtureFactory, registry, assembler, citationValidator);
        ReplayConsistencyExecutor replayExecutor =
                new ReplayConsistencyExecutor(experimentService, replayService, mapper, 10_000L);
        EvaluationService evaluationService = new EvaluationService(modelRegistry, dataset,
                metricCalculator, registry, evaluationRepository, modelExecutor, knowledgeExecutor,
                jobExecutor, citationExecutor, replayExecutor);

        return new Stack(mapper, registry, jobRepository, experimentService, runner, replayService,
                reportService, assembler, citationValidator, evaluationService, evaluationRepository,
                knowledgeRepository, dataset, fixtureFactory);
    }

    public record Stack(ObjectMapper mapper, ArtifactRegistry registry,
                        InMemoryExperimentJobRepository jobRepository, ExperimentService experimentService,
                        ReplayTestSupport.DeterministicPolarRunner runner, ReplayService replayService,
                        ReportService reportService, ReportDataAssembler assembler,
                        ReportCitationValidator citationValidator, EvaluationService evaluationService,
                        InMemoryEvaluationRepository evaluationRepository,
                        InMemoryWavePilotKnowledgeRepository knowledgeRepository,
                        EvaluationDataset dataset, EvaluationFixtureFactory fixtureFactory) {

        public org.example.wavepilot.experiment.model.ExperimentJob awaitJob(String jobId)
                throws InterruptedException {
            return IntegrationTestSupport.awaitJob(this, jobId);
        }

        public org.example.wavepilot.replay.ReplayRecord awaitReplay(String replayId)
                throws InterruptedException {
            return IntegrationTestSupport.awaitReplay(this, replayId);
        }
    }

    public static org.example.wavepilot.experiment.model.ExperimentJob awaitJob(Stack stack, String jobId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            org.example.wavepilot.experiment.model.ExperimentJob job =
                    stack.experimentService().get(jobId);
            if (job.getStatus().isTerminal()) return job;
            Thread.sleep(20);
        }
        throw new AssertionError("Job did not finish in time: " + jobId);
    }

    public static org.example.wavepilot.replay.ReplayRecord awaitReplay(Stack stack, String replayId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            org.example.wavepilot.replay.ReplayRecord record = stack.replayService().get(replayId);
            if (record.getStatus() != org.example.wavepilot.replay.ReplayStatus.RUNNING) return record;
            Thread.sleep(50);
        }
        throw new AssertionError("Replay did not finish in time: " + replayId);
    }

    /** Deterministic hash-based embedding for the in-memory knowledge repository. */
    public static final class DeterministicEmbeddingService implements WavePilotEmbeddingService {

        private static final int DIMENSIONS = 64;

        @Override
        public float[] embed(String text) {
            float[] vector = new float[DIMENSIONS];
            for (String term : terms(text)) {
                Random random = new Random(term.hashCode());
                for (int index = 0; index < DIMENSIONS; index++) {
                    vector[index] += random.nextFloat() * 2 - 1;
                }
            }
            double norm = 0;
            for (float value : vector) norm += (double) value * value;
            norm = Math.sqrt(norm);
            if (norm == 0) return vector;
            for (int index = 0; index < DIMENSIONS; index++) {
                vector[index] = (float) (vector[index] / norm);
            }
            return vector;
        }

        @Override
        public String providerDescription() { return "deterministic offline integration embedding"; }

        private List<String> terms(String text) {
            List<String> terms = new ArrayList<>();
            var matcher = TERM.matcher(text);
            while (matcher.find()) terms.add(matcher.group());
            return terms;
        }
    }
}
