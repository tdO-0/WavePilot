package org.example.wavepilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
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
import org.example.wavepilot.report.ReportCitationValidator;
import org.example.wavepilot.report.ReportDataAssembler;
import org.example.wavepilot.runner.MockExperimentRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/** Offline evaluation test stack; the only external boundary is the in-memory knowledge store. */
final class EvaluationTestSupport {

    private static final Pattern TERM = Pattern.compile("[\\p{IsHan}]|[A-Za-z0-9_]+");

    private EvaluationTestSupport() { }

    static Stack stack(Path root) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(root.toString(), mapper);
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator();
        ExperimentStateMachine stateMachine = new ExperimentStateMachine();
        InMemoryExperimentJobRepository jobRepository = new InMemoryExperimentJobRepository();
        MockExperimentRunner runner = new MockExperimentRunner(registry, mapper, specValidator);
        ResultValidator resultValidator = new ResultValidator(mapper, specValidator);
        ExperimentService experimentService = new ExperimentService(specValidator, stateMachine,
                jobRepository, runner, registry, resultValidator, mapper);
        ReplayFingerprintService fingerprints = new ReplayFingerprintService(mapper);
        MatlabTemplateDigest digest = new MatlabTemplateDigest();
        ReplayComparisonEvaluator replayEvaluator =
                new ReplayComparisonEvaluator(registry, mapper, fingerprints);
        InMemoryReplayRepository replayRepository = new InMemoryReplayRepository();
        ReplayService replayService = new ReplayService(experimentService, registry, fingerprints,
                replayEvaluator, digest, replayRepository, mapper, 1.0e-9);

        DeterministicEmbeddingService embedding = new DeterministicEmbeddingService();
        InMemoryWavePilotKnowledgeRepository knowledgeRepository =
                new InMemoryWavePilotKnowledgeRepository(embedding);
        EvaluationDataset dataset = new EvaluationDataset();
        EvaluationToolGuard toolGuard = new EvaluationToolGuard();
        EvaluationMetricCalculator metricCalculator = new EvaluationMetricCalculator();
        EvaluationFixtureFactory fixtureFactory =
                new EvaluationFixtureFactory(jobRepository, registry, mapper);
        ReportDataAssembler assembler = new ReportDataAssembler(registry, mapper);
        ReportCitationValidator citationValidator = new ReportCitationValidator(registry, mapper);
        EvaluationModelRegistry modelRegistry = new EvaluationModelRegistry(
                new ReferenceStubModel(), new RegressedStubModel(), java.util.Optional.empty());
        InMemoryEvaluationRepository evaluationRepository = new InMemoryEvaluationRepository();
        ModelDrivenCaseExecutor modelExecutor = new ModelDrivenCaseExecutor(specValidator, toolGuard);
        KnowledgeRetrievalExecutor knowledgeExecutor =
                new KnowledgeRetrievalExecutor(knowledgeRepository, dataset);
        JobCasesExecutor jobExecutor = new JobCasesExecutor(experimentService, mapper, 10_000L);
        CitationGroundingExecutor citationExecutor =
                new CitationGroundingExecutor(fixtureFactory, registry, assembler, citationValidator);
        ReplayConsistencyExecutor replayExecutor =
                new ReplayConsistencyExecutor(experimentService, replayService, mapper, 10_000L);
        EvaluationService evaluationService = new EvaluationService(modelRegistry, dataset,
                metricCalculator, registry, evaluationRepository, modelExecutor, knowledgeExecutor,
                jobExecutor, citationExecutor, replayExecutor);
        return new Stack(mapper, registry, dataset, evaluationService, evaluationRepository,
                knowledgeRepository, toolGuard, jobRepository, assembler, citationValidator);
    }

    record Stack(ObjectMapper mapper, ArtifactRegistry registry, EvaluationDataset dataset,
                 EvaluationService evaluationService, InMemoryEvaluationRepository repository,
                 InMemoryWavePilotKnowledgeRepository knowledgeRepository, EvaluationToolGuard toolGuard,
                 InMemoryExperimentJobRepository jobRepository, ReportDataAssembler assembler,
                 ReportCitationValidator citationValidator) { }

    /** Deterministic hash-based embedding: equal terms always produce equal vectors. */
    static final class DeterministicEmbeddingService implements WavePilotEmbeddingService {

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
        public String providerDescription() { return "deterministic offline evaluation embedding"; }

        private List<String> terms(String text) {
            List<String> terms = new ArrayList<>();
            var matcher = TERM.matcher(text);
            while (matcher.find()) terms.add(matcher.group());
            return terms;
        }
    }
}
