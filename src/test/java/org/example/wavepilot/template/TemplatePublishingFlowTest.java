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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end candidate flow with the fake smoke runner: generate -> validate -> smoke
 * (not executed) -> review -> explicit user approval -> ACTIVE and discoverable in the
 * registry without touching any Java map.
 */
class TemplatePublishingFlowTest {

    @TempDir Path root;

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private record Stack(CandidateTemplateRepository candidates, TemplateGenerationService generation,
                         CandidateValidationService validation, CandidateSmokeService smoke,
                         TemplatePublishingService publishing, TemplateRegistry registry,
                         ExperimentDefinitionRegistry definitions, TemplateGenerationService raw) { }

    private Stack stack() {
        ObjectMapper mapper = mapper();
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
        return new Stack(candidates, generation, validation, smoke, publishing, registry,
                definitions, generation);
    }

    @Test
    void theFullLifecycleFromNaturalLanguageToActive() {
        Stack stack = stack();
        TemplateCandidate candidate = stack.generation().generate(
                "新增一个 QPSK 在 AWGN 信道下的 BER 仿真模板");
        assertEquals(TemplateCandidateStatus.SMOKE_PENDING, candidate.status());

        TemplateCandidate validated = stack.validation().validate(candidate.candidateId());
        assertEquals(TemplateCandidateStatus.SMOKE_PENDING, validated.status());

        TemplateCandidate reviewed = stack.smoke().smoke(candidate.candidateId());
        assertEquals(TemplateCandidateStatus.REVIEW_REQUIRED, reviewed.status(),
                "fake runner must not fake SMOKE_PASSED");
        assertFalse(reviewed.realSmokeExecuted());
        assertTrue(reviewed.smokeReport().contains("MATLAB Smoke 未执行"));

        TemplateCandidate active = stack.publishing()
                .approveAndPublish(candidate.candidateId(), "user-zhang");
        assertEquals(TemplateCandidateStatus.ACTIVE, active.status());

        // Discoverable through the registry without any Java map change.
        TemplateRecord record = stack.registry().active("qpsk-awgn-ber").orElseThrow();
        assertEquals("1.0.0", record.version());
        assertEquals("qpsk-awgn-ber", record.experimentTypeId());
        assertFalse(record.algorithmValidated());
        assertFalse(record.operationalValidated(), "no real smoke -> not operationalValidated");
        assertTrue(stack.definitions().byTemplateId("qpsk-awgn-ber").isPresent(),
                "the definition must be registered for the declarative chain");
    }

    @Test
    void approvalRequiresAnExplicitApproverAndCannotBeAnonymous() {
        Stack stack = stack();
        TemplateCandidate candidate = stack.generation().generate("BER 模板");
        stack.validation().validate(candidate.candidateId());
        stack.smoke().smoke(candidate.candidateId());
        assertThrows(TemplatePublishingService.PublishingException.class,
                () -> stack.publishing().approveAndPublish(candidate.candidateId(), "  "));
    }

    @Test
    void tamperedCandidatesAreRejectedAtPublishTime() {
        Stack stack = stack();
        TemplateCandidate candidate = stack.generation().generate("BER 模板");
        stack.validation().validate(candidate.candidateId());
        stack.smoke().smoke(candidate.candidateId());
        // Tamper with the stored candidate content after validation.
        TemplateCandidate tampered = new TemplateCandidate(
                candidate.candidateId(), candidate.templateId(), candidate.experimentTypeId(),
                candidate.displayName(), candidate.version(), TemplateCandidateStatus.REVIEW_REQUIRED,
                candidate.source(), candidate.request(), candidate.definitionYaml(),
                candidate.manifestJson(), candidate.generationNotes(), candidate.assumptions(),
                candidate.unresolvedQuestions(),
                java.util.List.of(new TemplateCandidate.CandidateFile(
                        "matlab/run_experiment.m", "function run_experiment()\n  system('evil');\nend\n",
                        candidate.files().get(0).sha256())),
                candidate.securityFindings(), candidate.smokeReport(), candidate.realSmokeExecuted(),
                null, candidate.createdAt(), candidate.updatedAt());
        stack.candidates().save(tampered);

        TemplatePublishingService.PublishingException exception = assertThrows(
                TemplatePublishingService.PublishingException.class,
                () -> stack.publishing().approveAndPublish(candidate.candidateId(), "user-zhang"));
        assertTrue(exception.getMessage().contains("hash mismatch") || exception.getMessage().contains("tampered"));
    }

    @Test
    void versionConflictsAreRejectedAndHistoryIsImmutable() throws Exception {
        Stack stack = stack();
        TemplateCandidate first = stack.generation().generate("BER 模板");
        stack.validation().validate(first.candidateId());
        stack.smoke().smoke(first.candidateId());
        stack.publishing().approveAndPublish(first.candidateId(), "user-zhang");

        TemplateCandidate second = stack.generation().generate("BER 模板");
        stack.validation().validate(second.candidateId());
        stack.smoke().smoke(second.candidateId());
        TemplatePublishingService.PublishingException exception = assertThrows(
                TemplatePublishingService.PublishingException.class,
                () -> stack.publishing().approveAndPublish(second.candidateId(), "user-zhang"));
        assertTrue(exception.getMessage().contains("Version conflict"));

        // Published files exist on disk and survive a fresh registry.
        Path published = root.resolve("data/wavepilot/templates/approved")
                .resolve(first.templateId()).resolve("1.0.0");
        assertTrue(Files.isDirectory(published));
        assertTrue(Files.exists(published.resolve("publication-record.json")));
        assertTrue(Files.exists(published.resolve("experiment-definition.yaml")),
                "the declarative definition must be persisted with the template");
        TemplateRegistry restarted = new TemplateRegistry(new FileSystemTemplateRepository(
                new TemplateRootProperties(root.resolve("data/wavepilot/templates").toString()), mapper()));
        assertTrue(restarted.active(first.templateId()).isPresent(),
                "published template must survive a restart");

        // The declarative definition must be restorable after a restart too.
        ExperimentDefinitionRegistry freshDefinitions = new ExperimentDefinitionRegistry();
        new ApprovedTemplateDefinitionLoader(new FileSystemTemplateRepository(
                new TemplateRootProperties(root.resolve("data/wavepilot/templates").toString()), mapper()),
                freshDefinitions, new ExperimentDefinitionParser(),
                new ExperimentDefinitionValidator()).loadPublishedDefinitions();
        assertTrue(freshDefinitions.byTemplateId(first.templateId()).isPresent(),
                "published declarative definitions must survive a restart");
    }

    @Test
    void rejectionIsExplicitAndNeverPublishes() {
        Stack stack = stack();
        TemplateCandidate candidate = stack.generation().generate("BER 模板");
        stack.validation().validate(candidate.candidateId());
        TemplateCandidate rejected = stack.publishing().reject(candidate.candidateId(), "人工否决");
        assertEquals(TemplateCandidateStatus.REJECTED, rejected.status());
        assertTrue(stack.registry().list(null).isEmpty());
    }
}
