package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.DeclarativeResultContractValidator;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.report.DeclarativeMetricsExtractor;
import org.example.wavepilot.replay.DeclarativeComparisonMetrics;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof: after publishing a declarative DEMO template, and WITHOUT modifying any Java map,
 * the template is discoverable in the catalog and works through the generic Validator,
 * result contract, metrics extractor and replay comparison chain.
 */
class DeclarativePublishedTemplateChainTest {

    @TempDir Path root;

    @Test
    void publishedDeclarativeTemplateWorksThroughTheGenericChain() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CandidateTemplateRepository candidates = new CandidateTemplateRepository();
        CandidateStateMachine machine = new CandidateStateMachine();
        ExperimentDefinitionParser parser = new ExperimentDefinitionParser();
        ExperimentDefinitionValidator definitionValidator = new ExperimentDefinitionValidator();
        ExperimentDefinitionRegistry definitions = new ExperimentDefinitionRegistry();
        TemplateGenerationService generation = new TemplateGenerationService(
                new StubTemplateGenerationModel(), candidates, machine, parser, definitionValidator,
                definitions, mapper);
        CandidateValidationService validation = new CandidateValidationService(candidates,
                new TemplateSecurityScanner(), parser, definitionValidator, mapper);
        CandidateSmokeService smoke = new CandidateSmokeService(candidates, machine,
                new FakeCandidateSmokeRunner());
        TemplateRootProperties props = new TemplateRootProperties(
                root.resolve("data/wavepilot/templates").toString());
        FileSystemTemplateRepository files = new FileSystemTemplateRepository(props, mapper);
        TemplateRegistry registry = new TemplateRegistry(files);
        TemplatePublishingService publishing = new TemplatePublishingService(candidates, machine,
                registry, files, definitions, parser, definitionValidator, mapper);

        // 1. Publish a DEMO template through the full candidate lifecycle.
        TemplateCandidate candidate = generation.generate("QPSK AWGN BER 模板");
        validation.validate(candidate.candidateId());
        smoke.smoke(candidate.candidateId());
        publishing.approveAndPublish(candidate.candidateId(), "user-demo");
        assertTrue(registry.active("qpsk-awgn-ber").isPresent(), "template must be discoverable");

        // 2. A spec of the declarative type passes the generic Java validator.
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "chain test",
                "qpsk-awgn-ber", Map.of("ebNoStart", 0.0, "ebNoEnd", 12.0, "ebNoStep", 0.5,
                "frames", 1000));
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator(definitions);
        ValidationResult result = specValidator.validate(spec);
        assertTrue(result.valid(), "generic validator must accept the declarative spec: " + result.errors());

        // 3. A result CSV of the declarative contract passes the generic result contract.
        Path jobsRoot = root.resolve("jobs");
        Path jobDirectory = jobsRoot.resolve("JOB-CHAIN");
        Files.createDirectories(jobDirectory);
        Path csv = jobDirectory.resolve("accuracy.csv");
        Files.writeString(csv, "ebNo,berSim,berTheory\n0,0.1,0.09\n1,0.2,0.21\n", StandardCharsets.UTF_8);
        Map<org.example.wavepilot.artifact.ArtifactType, Path> byType =
                new EnumMap<>(org.example.wavepilot.artifact.ArtifactType.class);
        byType.put(org.example.wavepilot.artifact.ArtifactType.ACCURACY_CSV, csv);
        Path runLog = jobDirectory.resolve("run.log");
        Files.writeString(runLog, "demo log", StandardCharsets.UTF_8);
        byType.put(org.example.wavepilot.artifact.ArtifactType.RUN_LOG, runLog);
        List<String> errors = new ArrayList<>();
        new DeclarativeResultContractValidator(definitions, mapper).validate(
                job("JOB-CHAIN", spec), byType, errors);
        assertTrue(errors.isEmpty(), "generic contract must accept the CSV: " + errors);

        // 4. Metrics extraction works for the declarative type.
        org.example.wavepilot.artifact.ArtifactRegistry registryFiles =
                new org.example.wavepilot.artifact.ArtifactRegistry(jobsRoot.toString(), mapper);
        Path summary = jobDirectory.resolve("summary.json");
        Files.writeString(summary, "{}", StandardCharsets.UTF_8);
        var csvRecord = registryFiles.register("JOB-CHAIN",
                org.example.wavepilot.artifact.ArtifactType.ACCURACY_CSV, csv);
        var summaryRecord = registryFiles.register("JOB-CHAIN",
                org.example.wavepilot.artifact.ArtifactType.SUMMARY_JSON, summary);
        var extracted = new DeclarativeMetricsExtractor(definitions, registryFiles, mapper)
                .extract(registryFiles, job("JOB-CHAIN", spec), List.of(csvRecord, summaryRecord));
        assertEquals(2, extracted.rows().size());
        assertTrue(extracted.summary().get("metricValues").size() == 1);

        // 5. Replay comparison metrics come from the declared definition.
        var replayMetrics = new DeclarativeComparisonMetrics(definitions, "qpsk-awgn-ber").metrics();
        assertEquals(1, replayMetrics.size());
        assertEquals("berSim", replayMetrics.get(0).name());
    }

    private org.example.wavepilot.experiment.model.ExperimentJob job(String jobId, ExperimentSpec spec) {
        org.example.wavepilot.experiment.model.ExperimentPlan plan =
                new org.example.wavepilot.experiment.model.ExperimentPlan("PLAN-CHAIN", spec,
                        "qpsk-awgn-ber", 2, List.of("RUN"), Instant.now());
        org.example.wavepilot.experiment.model.ExperimentJob job =
                new org.example.wavepilot.experiment.model.ExperimentJob(jobId, spec, plan);
        job.changeStatus(org.example.wavepilot.experiment.model.ExperimentStatus.SUCCEEDED, "fixture");
        return job;
    }
}
