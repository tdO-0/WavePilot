package org.example.wavepilot.template;

import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.example.wavepilot.template.publish.TemplatePublishingService;
import org.example.wavepilot.template.smoke.CandidateSmokeService;
import org.example.wavepilot.template.validation.CandidateValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wavepilot")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TemplateCatalogController {

    private final TemplateCatalogService catalog;
    private final TemplateGenerationService generation;
    private final CandidateValidationService validation;
    private final CandidateSmokeService smoke;
    private final TemplatePublishingService publishing;
    private final TemplateRegistry registry;

    public TemplateCatalogController(TemplateCatalogService catalog,
                                     TemplateGenerationService generation,
                                     CandidateValidationService validation,
                                     CandidateSmokeService smoke,
                                     TemplatePublishingService publishing,
                                     TemplateRegistry registry) {
        this.catalog = catalog;
        this.generation = generation;
        this.validation = validation;
        this.smoke = smoke;
        this.publishing = publishing;
        this.registry = registry;
    }

    @GetMapping("/templates")
    public List<TemplateRecord> templates(@RequestParam(required = false) TemplateSource source,
                                          @RequestParam(required = false) TemplateStatus status,
                                          @RequestParam(required = false) String experimentTypeId,
                                          @RequestParam(required = false) String classification,
                                          @RequestParam(required = false) Boolean operationalValidated,
                                          @RequestParam(required = false) Boolean algorithmValidated) {
        return catalog.listTemplates(source, status, experimentTypeId, classification,
                operationalValidated, algorithmValidated);
    }

    @GetMapping("/templates/{templateId}")
    public TemplateCatalogService.TemplateDetailView template(@PathVariable String templateId) {
        return catalog.templateDetail(templateId);
    }

    @GetMapping("/templates/{templateId}/versions/{version}")
    public TemplateRecord version(@PathVariable String templateId, @PathVariable String version) {
        return catalog.version(templateId, version);
    }

    @GetMapping("/template-candidates")
    public List<TemplateCandidate> candidates() {
        return catalog.listCandidates();
    }

    @GetMapping("/template-candidates/{candidateId}")
    public TemplateCandidate candidate(@PathVariable String candidateId) {
        return catalog.candidate(candidateId);
    }

    @PostMapping("/template-candidates/generate")
    public TemplateCandidate generate(@RequestBody Map<String, String> body) {
        return generation.generate(body.get("request"));
    }

    @PostMapping("/template-candidates/{candidateId}/validate")
    public TemplateCandidate validate(@PathVariable String candidateId) {
        return validation.validate(candidateId);
    }

    @PostMapping("/template-candidates/{candidateId}/smoke")
    public TemplateCandidate smoke(@PathVariable String candidateId) {
        return smoke.smoke(candidateId);
    }

    @PostMapping("/template-candidates/{candidateId}/approve")
    public TemplateCandidate approve(@PathVariable String candidateId,
                                     @RequestBody(required = false) Map<String, String> body) {
        return publishing.approveAndPublish(candidateId, body == null ? null : body.get("approvedBy"));
    }

    @PostMapping("/template-candidates/{candidateId}/reject")
    public TemplateCandidate reject(@PathVariable String candidateId,
                                    @RequestBody(required = false) Map<String, String> body) {
        return publishing.reject(candidateId, body == null ? null : body.get("reason"));
    }

    @PostMapping("/templates/{templateId}/deactivate")
    public Map<String, String> deactivate(@PathVariable String templateId) {
        registry.deactivate(templateId);
        return Map.of("templateId", templateId, "status", "INACTIVE");
    }

    @PostMapping("/templates/{templateId}/rollback")
    public Map<String, String> rollback(@PathVariable String templateId,
                                        @RequestBody Map<String, String> body) {
        registry.rollback(templateId, body.get("version"));
        return Map.of("templateId", templateId, "activeVersion", body.get("version"));
    }
}
