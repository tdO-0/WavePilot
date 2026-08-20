package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same templateId can be generated again: the service auto-bumps to the next patch version. */
class TemplateVersionBumpTest {

    @TempDir Path root;

    private record Stack(TemplateGenerationService generation, TemplateRegistry registry,
                         CandidateValidationService validation, CandidateSmokeService smoke,
                         TemplatePublishingService publishing, CandidateTemplateRepository candidates) { }

    private Stack stack() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CandidateTemplateRepository candidates = new CandidateTemplateRepository();
        CandidateStateMachine machine = new CandidateStateMachine();
        ExperimentDefinitionParser parser = new ExperimentDefinitionParser();
        ExperimentDefinitionValidator definitionValidator = new ExperimentDefinitionValidator();
        ExperimentDefinitionRegistry definitions = new ExperimentDefinitionRegistry();
        TemplateRootProperties props = new TemplateRootProperties(
                root.resolve("data/wavepilot/templates").toString());
        FileSystemTemplateRepository files = new FileSystemTemplateRepository(props, mapper);
        TemplateRegistry registry = new TemplateRegistry(files);
        TemplateGenerationService generation = new TemplateGenerationService(
                new StubTemplateGenerationModel(), candidates, machine, parser, definitionValidator,
                definitions, registry, mapper);
        CandidateValidationService validation = new CandidateValidationService(candidates,
                new TemplateSecurityScanner(), parser, definitionValidator, mapper);
        CandidateSmokeService smoke = new CandidateSmokeService(candidates, machine,
                new FakeCandidateSmokeRunner());
        TemplatePublishingService publishing = new TemplatePublishingService(candidates, machine,
                registry, files, definitions, parser, definitionValidator, mapper);
        return new Stack(generation, registry, validation, smoke, publishing, candidates);
    }

    @Test
    void firstGenerationKeepsTheModelVersion() {
        Stack stack = stack();
        TemplateCandidate candidate = stack.generation().generate("QPSK AWGN BER 模板");
        assertEquals("1.0.0", candidate.version());
        assertTrue(candidate.definitionYaml().contains("version: 1.0.0"));
    }

    @Test
    void secondGenerationBumpsToTheNextPatchVersion() throws Exception {
        Stack stack = stack();
        // Publish 1.0.0 first.
        TemplateCandidate first = stack.generation().generate("QPSK AWGN BER 模板");
        stack.validation().validate(first.candidateId());
        stack.smoke().smoke(first.candidateId());
        stack.publishing().approveAndPublish(first.candidateId(), "user-bump");

        TemplateCandidate second = stack.generation().generate("QPSK AWGN BER 模板");
        assertEquals("1.0.1", second.version(), "same templateId must auto-bump the version");
        assertTrue(second.definitionYaml().contains("version: 1.0.1"),
                "definition YAML version must be rewritten");
        assertTrue(second.manifestJson().contains("\"templateVersion\": \"1.0.1\""),
                "manifest version must be rewritten");
        assertTrue(second.generationNotes().contains("版本自动递增"),
                "generation notes must explain the bump");
        assertEquals(TemplateCandidateStatus.SMOKE_PENDING, second.status());
    }

    @Test
    void bumpedVersionCanBePublishedWithoutConflict() throws Exception {
        Stack stack = stack();
        TemplateCandidate first = stack.generation().generate("QPSK AWGN BER 模板");
        stack.validation().validate(first.candidateId());
        stack.smoke().smoke(first.candidateId());
        stack.publishing().approveAndPublish(first.candidateId(), "user-bump");

        TemplateCandidate second = stack.generation().generate("QPSK AWGN BER 模板");
        stack.validation().validate(second.candidateId());
        stack.smoke().smoke(second.candidateId());
        TemplateCandidate active = stack.publishing().approveAndPublish(second.candidateId(), "user-bump");

        assertEquals(TemplateCandidateStatus.ACTIVE, active.status());
        assertEquals("1.0.1", stack.registry().active("qpsk-awgn-ber").orElseThrow().version());
        assertEquals(2, stack.registry().versions("qpsk-awgn-ber").size(),
                "both versions must coexist; history is never deleted");
    }

    @Test
    void bumpStartsWithTheHighestExistingVersion() throws Exception {
        Stack stack = stack();
        TemplateRecord v1 = record("1.0.0", TemplateStatus.INACTIVE);
        TemplateRecord v2 = record("1.0.1", TemplateStatus.ACTIVE);
        stack.registry().registerApproved(v1);
        stack.registry().registerApproved(v2);

        TemplateCandidate candidate = stack.generation().generate("QPSK AWGN BER 模板");
        assertEquals("1.0.2", candidate.version());
        assertFalse(candidate.definitionYaml().contains("version: 1.0.0"));
    }

    private TemplateRecord record(String version, TemplateStatus status) {
        return new TemplateRecord("qpsk-awgn-ber", "qpsk-awgn-ber", "QPSK 演示", version,
                "run_experiment", "demo", TemplateSource.AGENT_GENERATED, status,
                "SIMULATION_BASELINE", false, false, Instant.now(), Instant.now(),
                "def", "tpl", version, List.of("ebNoStart"), List.of("ACCURACY_CSV"));
    }
}
