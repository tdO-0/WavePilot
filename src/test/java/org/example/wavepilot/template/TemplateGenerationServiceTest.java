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
import org.example.wavepilot.template.generation.TemplateGenerationModel;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateGenerationServiceTest {

    private TemplateGenerationService service() {
        return new TemplateGenerationService(new StubTemplateGenerationModel(),
                new CandidateTemplateRepository(), new CandidateStateMachine(),
                new ExperimentDefinitionParser(), new ExperimentDefinitionValidator(),
                new ExperimentDefinitionRegistry(), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void aNaturalLanguageRequestProducesAValidatedCandidate() {
        TemplateCandidate candidate = service().generate(
                "新增一个 QPSK 在 AWGN 信道下的 BER 仿真模板，Eb/N0 从 0 到 12 dB");

        assertEquals(TemplateCandidateStatus.SMOKE_PENDING, candidate.status(),
                "static validation passed -> waiting for smoke, never ACTIVE");
        assertEquals("qpsk-awgn-ber", candidate.templateId());
        assertEquals("qpsk-awgn-ber", candidate.experimentTypeId());
        assertTrue(candidate.files().stream().anyMatch(file ->
                file.relativePath().equals("matlab/run_experiment.m")));
        assertTrue(candidate.files().stream().allMatch(file ->
                file.sha256() != null && file.sha256().matches("[0-9a-f]{64}")));
        assertFalse(candidate.realSmokeExecuted(), "no MATLAB smoke was executed");
        assertTrue(candidate.definitionYaml().contains("algorithmValidated: false"));
    }

    @Test
    void aBecRequestProducesABecCandidate() {
        TemplateCandidate candidate = service().generate(
                "新增一个二进制删除信道（BEC）的 BER 仿真模板，删除概率 0 到 0.5 步长 0.1");
        assertEquals("bec-ber", candidate.templateId());
        assertEquals(TemplateCandidateStatus.SMOKE_PENDING, candidate.status());
        assertTrue(candidate.definitionYaml().contains("erasureStart"),
                "BEC template must declare erasure parameters");
        assertTrue(candidate.definitionYaml().contains("algorithmValidated: false"));
        assertTrue(candidate.files().stream().anyMatch(file ->
                file.relativePath().equals("matlab/run_experiment.m")));
    }

    @Test
    void unsafePathsAndManifestMismatchesAreRejected() {
        TemplateGenerationService service = service();
        TemplateGenerationModel unsafe = new TemplateGenerationModel() {
            @Override public String name() { return "unsafe"; }
            @Override public TemplateGenerationResult generate(String request) {
                return new TemplateGenerationResult("tpl", "tpl", "名字", "1.0.0", "x",
                        DeclarativeTestSupport.DEMO_DEFINITION_YAML,
                        "{\"templateName\":\"tpl\",\"experimentType\":\"tpl\"}",
                        List.of(new GeneratedFile("../escape.m", "x")),
                        "n", List.of(), List.of());
            }
        };
        TemplateGenerationService unsafeService = new TemplateGenerationService(
                new StubTemplateGenerationModel() {
                    @Override public TemplateGenerationResult generate(String request) {
                        return unsafe.generate(request);
                    }
                },
                new CandidateTemplateRepository(), new CandidateStateMachine(),
                new ExperimentDefinitionParser(), new ExperimentDefinitionValidator(),
                new ExperimentDefinitionRegistry(), new ObjectMapper().findAndRegisterModules());
        assertThrows(TemplateGenerationService.TemplateGenerationException.class,
                () -> unsafeService.generate("模板"),
                "unsafe relative paths must be rejected");

        // Manifest/definition mismatch must land in VALIDATION_FAILED, not SMOKE_PENDING.
        TemplateGenerationModel broken = new TemplateGenerationModel() {
            @Override public String name() { return "broken"; }
            @Override public TemplateGenerationResult generate(String request) {
                return new TemplateGenerationResult("tpl-a", "tpl-b", "名字", "1.0.0", "x",
                        DeclarativeTestSupport.DEMO_DEFINITION_YAML,
                        "{\"templateName\":\"other\",\"experimentType\":\"tpl-b\"}",
                        List.of(new GeneratedFile("matlab/run_experiment.m", "function run_experiment()\nend\n")),
                        "n", List.of(), List.of());
            }
        };
        TemplateGenerationService brokenService = new TemplateGenerationService(
                new StubTemplateGenerationModel() {
                    @Override public TemplateGenerationModel.TemplateGenerationResult generate(String request) {
                        return broken.generate(request);
                    }
                },
                new CandidateTemplateRepository(), new CandidateStateMachine(),
                new ExperimentDefinitionParser(), new ExperimentDefinitionValidator(),
                new ExperimentDefinitionRegistry(), new ObjectMapper().findAndRegisterModules());
        TemplateCandidate candidate = brokenService.generate("x");
        assertEquals(TemplateCandidateStatus.VALIDATION_FAILED, candidate.status());
        assertTrue(candidate.failureReason().contains("templateName"));
    }

    @Test
    void missingEntryPointFailsValidation() {
        TemplateGenerationModel noEntry = new TemplateGenerationModel() {
            @Override public String name() { return "no-entry"; }
            @Override public TemplateGenerationResult generate(String request) {
                return new TemplateGenerationResult("tpl", "tpl", "名字", "1.0.0", "x",
                        DeclarativeTestSupport.DEMO_DEFINITION_YAML,
                        "{\"templateName\":\"tpl\",\"experimentType\":\"tpl\","
                                + "\"algorithmValidated\":false}",
                        List.of(new GeneratedFile("matlab/other.m", "x")),
                        "n", List.of(), List.of());
            }
        };
        TemplateGenerationService service = new TemplateGenerationService(
                new StubTemplateGenerationModel() {
                    @Override public TemplateGenerationResult generate(String request) {
                        return noEntry.generate(request);
                    }
                },
                new CandidateTemplateRepository(), new CandidateStateMachine(),
                new ExperimentDefinitionParser(), new ExperimentDefinitionValidator(),
                new ExperimentDefinitionRegistry(), new ObjectMapper().findAndRegisterModules());
        TemplateCandidate candidate = service.generate("x");
        assertEquals(TemplateCandidateStatus.VALIDATION_FAILED, candidate.status());
        assertTrue(candidate.failureReason().contains("run_experiment.m"));
    }

    @Test
    void candidatesAreNeverActiveAndRequireExplicitApproval() {
        TemplateCandidate candidate = service().generate("BER 模板");
        assertEquals(TemplateCandidateStatus.SMOKE_PENDING, candidate.status());
        // The candidate repository holds it; the formal registry stays untouched.
        assertTrue(new ExperimentDefinitionRegistry().all().isEmpty());
    }
}
