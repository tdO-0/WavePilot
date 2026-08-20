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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateCatalogApiTest {

    @TempDir Path root;

    @Test
    void exposesAllRequiredTemplateRoutes() {
        assertRoute(TemplateCatalogController.class, "templates", GetMapping.class, "/templates");
        assertRoute(TemplateCatalogController.class, "template", GetMapping.class, "/templates/{templateId}");
        assertRoute(TemplateCatalogController.class, "version", GetMapping.class,
                "/templates/{templateId}/versions/{version}");
        assertRoute(TemplateCatalogController.class, "candidates", GetMapping.class, "/template-candidates");
        assertRoute(TemplateCatalogController.class, "candidate", GetMapping.class,
                "/template-candidates/{candidateId}");
        assertRoute(TemplateCatalogController.class, "generate", PostMapping.class,
                "/template-candidates/generate");
        assertRoute(TemplateCatalogController.class, "validate", PostMapping.class,
                "/template-candidates/{candidateId}/validate");
        assertRoute(TemplateCatalogController.class, "smoke", PostMapping.class,
                "/template-candidates/{candidateId}/smoke");
        assertRoute(TemplateCatalogController.class, "approve", PostMapping.class,
                "/template-candidates/{candidateId}/approve");
        assertRoute(TemplateCatalogController.class, "reject", PostMapping.class,
                "/template-candidates/{candidateId}/reject");
        assertRoute(TemplateCatalogController.class, "deactivate", PostMapping.class,
                "/templates/{templateId}/deactivate");
        assertRoute(TemplateCatalogController.class, "rollback", PostMapping.class,
                "/templates/{templateId}/rollback");
    }

    @Test
    void theCatalogAnswersWhatTemplatesExistAfterPublishing() throws Exception {
        Stack stack = stack();
        TemplateCandidate candidate = stack.generation().generate("新增一个 QPSK AWGN BER 模板");
        stack.validation().validate(candidate.candidateId());
        stack.smoke().smoke(candidate.candidateId());
        stack.publishing().approveAndPublish(candidate.candidateId(), "user-zhang");

        // List endpoint exposes the published template with its boundaries.
        List<TemplateRecord> templates = stack.catalog().listTemplates(null, null, null,
                null, null, null);
        assertTrue(templates.stream().anyMatch(record -> record.templateId().equals("qpsk-awgn-ber")));
        TemplateRecord published = templates.stream()
                .filter(record -> record.templateId().equals("qpsk-awgn-ber")).findFirst().orElseThrow();
        assertEquals("1.0.0", published.activeVersion());
        assertEquals(TemplateSource.AGENT_GENERATED, published.source());
        assertEquals(false, published.algorithmValidated());
        assertEquals(false, published.operationalValidated());

        // Detail endpoint returns parameters, outputs, metrics, replay and versions.
        TemplateCatalogService.TemplateDetailView detail = stack.catalog().templateDetail("qpsk-awgn-ber");
        assertNotNull(detail.definition());
        assertEquals(4, detail.definition().parameters().size());
        assertEquals(List.of("ebNo", "berSim", "berTheory"),
                detail.definition().outputs().requiredColumns());
        assertEquals(1, detail.definition().metrics().size());
        assertEquals(1, detail.definition().replay().size());
        assertEquals(1, detail.versions().size());

        // Version endpoint works.
        assertEquals("1.0.0", stack.catalog().version("qpsk-awgn-ber", "1.0.0").version());

        // Candidates endpoint shows lifecycle state.
        List<TemplateCandidate> candidates = stack.catalog().listCandidates();
        assertEquals(1, candidates.size());
        assertEquals(TemplateCandidateStatus.ACTIVE, candidates.get(0).status());
        assertNotNull(stack.catalog().candidate(candidates.get(0).candidateId()));

        // JSON never leaks absolute paths or the root directory.
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        assertTrue(!json.contains(root.toAbsolutePath().toString().replace("\\", "/"))
                        && !json.contains("C:\\") && !json.contains("D:\\"),
                "template JSON must not expose local absolute paths");
    }

    private record Stack(TemplateGenerationService generation, CandidateValidationService validation,
                         CandidateSmokeService smoke, TemplatePublishingService publishing,
                         TemplateCatalogService catalog) { }

    private Stack stack() {
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
        TemplateCatalogService catalog = new TemplateCatalogService(registry, definitions, candidates);
        return new Stack(generation, validation, smoke, publishing, catalog);
    }

    private <A extends java.lang.annotation.Annotation> void assertRoute(
            Class<?> controller, String methodName, Class<A> annotationType, String route) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        A annotation = method.getAnnotation(annotationType);
        assertNotNull(annotation);
        String[] values = annotation instanceof GetMapping get ? get.value() : ((PostMapping) annotation).value();
        assertTrue(Arrays.asList(values).contains(route));
    }
}
