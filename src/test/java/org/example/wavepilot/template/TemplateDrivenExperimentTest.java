package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.runner.MockExperimentRunner;
import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "last mile": after a declarative template is published, an ExperimentSpec of that
 * type resolves its plan to the ACTIVE templateId, and the MATLAB runner copies template
 * files from the approved filesystem directory instead of the classpath.
 */
class TemplateDrivenExperimentTest {

    @TempDir Path root;

    @Test
    void declarativeSpecResolvesItsPlanToTheActiveTemplateId() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExperimentDefinitionRegistry definitions = new ExperimentDefinitionRegistry();
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator(definitions);
        ExperimentStateMachine stateMachine = new ExperimentStateMachine();
        InMemoryExperimentJobRepository jobs = new InMemoryExperimentJobRepository();
        ArtifactRegistry artifacts = new ArtifactRegistry(root.resolve("artifacts").toString(), mapper);
        MockExperimentRunner runner = new MockExperimentRunner(artifacts, mapper, specValidator);
        ResultValidator resultValidator = new ResultValidator(mapper, specValidator);
        TemplateRegistry registry = registryWithPublishedDemo(root, mapper, definitions);

        // Without a registry the legacy behavior is unchanged.
        ExperimentService legacy = new ExperimentService(specValidator, stateMachine, jobs, runner,
                artifacts, resultValidator, mapper);
        assertEquals("mock-polar-k-v1",
                legacy.previewPlan(org.example.wavepilot.WavePilotTestFixtures.validSpec())
                        .experimentTemplateVersion());

        // With a registry, a declarative spec resolves to the ACTIVE templateId.
        ExperimentService service = new ExperimentService(specValidator, stateMachine, jobs, runner,
                artifacts, resultValidator, mapper, registry);
        ExperimentSpec spec = declarativeSpec();
        ExperimentPlan plan = service.previewPlan(spec);
        assertEquals("qpsk-awgn-ber", plan.experimentTemplateVersion(),
                "declarative plan must point at the ACTIVE templateId");
        assertTrue(registry.active("qpsk-awgn-ber").isPresent());
    }

    @Test
    void matlabRunnerCopiesApprovedFilesystemFilesIntoTheJobDirectory() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExperimentDefinitionRegistry definitions = new ExperimentDefinitionRegistry();
        TemplateRegistry registry = registryWithPublishedDemo(root, mapper, definitions);
        TemplateRootProperties props = new TemplateRootProperties(
                root.resolve("data/wavepilot/templates").toString());
        FileSystemTemplateRepository files = new FileSystemTemplateRepository(props, mapper);
        // Write an extra approved file to prove filesystem (not classpath) sourcing.
        Path approved = files.approvedDirectory("qpsk-awgn-ber", "1.0.0");
        Files.writeString(approved.resolve("matlab/run_experiment.m"),
                "function run_experiment(inputFile, outputDir)\n  data = jsondecode(fileread(inputFile));\nend\n",
                StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("matlab/helper.m"),
                "function out = helper(x)\n  out = x;\nend\n", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("README.md"), "approved fixture\n", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("experiment-definition.yaml"),
                DeclarativeTestSupport.DEMO_DEFINITION_YAML, StandardCharsets.UTF_8);

        ExperimentSpecValidator specValidator = new ExperimentSpecValidator();
        org.example.wavepilot.runner.LocalMatlabExperimentRunner runner =
                new org.example.wavepilot.runner.LocalMatlabExperimentRunner(
                        new ArtifactRegistry(root.resolve("artifacts2").toString(), mapper), mapper,
                        specValidator, matlabProperties(), files, registry);
        Path jobDirectory = root.resolve("job-dir");
        Files.createDirectories(jobDirectory);
        runner.copyApprovedTemplateFiles("qpsk-awgn-ber", jobDirectory);

        // matlab/ sources are flattened into the job root so -batch can find the entry point.
        assertTrue(Files.exists(jobDirectory.resolve("run_experiment.m")),
                "the entry point must land in the job root");
        assertTrue(Files.exists(jobDirectory.resolve("helper.m")));
        assertFalse(Files.exists(jobDirectory.resolve("matlab/run_experiment.m")),
                "matlab/ sources must be flattened, not kept nested");
        assertTrue(Files.exists(jobDirectory.resolve("experiment-definition.yaml")),
                "definition travels with the approved template files");
        // Nothing may escape the job directory.
        assertTrue(Files.walk(jobDirectory).allMatch(path ->
                path.startsWith(jobDirectory.toAbsolutePath().normalize())));
    }

    private TemplateRegistry registryWithPublishedDemo(Path root, ObjectMapper mapper,
                                                       ExperimentDefinitionRegistry definitions) {
        TemplateRootProperties props = new TemplateRootProperties(
                root.resolve("data/wavepilot/templates").toString());
        FileSystemTemplateRepository files = new FileSystemTemplateRepository(props, mapper);
        TemplateRegistry registry = new TemplateRegistry(files);
        CandidateTemplateRepository candidates = new CandidateTemplateRepository();
        CandidateStateMachine machine = new CandidateStateMachine();
        ExperimentDefinitionParser parser = new ExperimentDefinitionParser();
        ExperimentDefinitionValidator definitionValidator = new ExperimentDefinitionValidator();
        TemplateGenerationService generation = new TemplateGenerationService(
                new StubTemplateGenerationModel(), candidates, machine, parser, definitionValidator,
                definitions, registry, mapper);
        CandidateValidationService validation = new CandidateValidationService(candidates,
                new TemplateSecurityScanner(), parser, definitionValidator, mapper);
        CandidateSmokeService smoke = new CandidateSmokeService(candidates, machine,
                new FakeCandidateSmokeRunner());
        TemplatePublishingService publishing = new TemplatePublishingService(candidates, machine,
                registry, files, definitions, parser, definitionValidator, mapper);
        TemplateCandidate candidate = generation.generate("QPSK AWGN BER 模板");
        validation.validate(candidate.candidateId());
        smoke.smoke(candidate.candidateId());
        publishing.approveAndPublish(candidate.candidateId(), "test-user");
        return registry;
    }

    private ExperimentSpec declarativeSpec() {
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "template driven",
                "qpsk-awgn-ber", Map.of("ebNoStart", 0.0, "ebNoEnd", 4.0, "ebNoStep", 0.5,
                "frames", 200));
    }

    private org.example.wavepilot.runner.LocalMatlabRunnerProperties matlabProperties() {
        org.example.wavepilot.runner.LocalMatlabRunnerProperties props =
                new org.example.wavepilot.runner.LocalMatlabRunnerProperties();
        props.setExecutable("matlab");
        return props;
    }
}
