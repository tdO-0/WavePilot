package org.example.wavepilot.template;

import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * Read-side service assembling template views (registry records plus declarative definition
 * content). Never exposes absolute paths; the agent tools talk to the platform through this
 * facade only.
 */
@Service
public class TemplateCatalogService {

    private final TemplateRegistry registry;
    private final ExperimentDefinitionRegistry definitionRegistry;
    private final CandidateTemplateRepository candidateRepository;

    public TemplateCatalogService(TemplateRegistry registry,
                                  ExperimentDefinitionRegistry definitionRegistry,
                                  CandidateTemplateRepository candidateRepository) {
        this.registry = registry;
        this.definitionRegistry = definitionRegistry;
        this.candidateRepository = candidateRepository;
    }

    public List<TemplateRecord> listTemplates(TemplateSource source, TemplateStatus status,
                                              String experimentTypeId, String classification,
                                              Boolean operationalValidated, Boolean algorithmValidated) {
        Predicate<TemplateRecord> filter = record -> true;
        if (source != null) filter = filter.and(record -> record.source() == source);
        if (status != null) filter = filter.and(record -> record.status() == status);
        if (experimentTypeId != null) filter = filter.and(record -> record.experimentTypeId().equals(experimentTypeId));
        if (classification != null) filter = filter.and(record -> record.classification().equals(classification));
        if (operationalValidated != null) filter = filter.and(record -> record.operationalValidated() == operationalValidated);
        if (algorithmValidated != null) filter = filter.and(record -> record.algorithmValidated() == algorithmValidated);
        return registry.list(filter);
    }

    public TemplateDetailView templateDetail(String templateId) {
        TemplateRecord active = registry.active(templateId)
                .orElseThrow(() -> new NoSuchElementException("Template has no active version: " + templateId));
        ExperimentDefinition definition = definitionRegistry.byTemplateId(templateId).orElse(null);
        return new TemplateDetailView(active, registry.versions(templateId),
                definition, registry.activeVersions());
    }

    public TemplateRecord version(String templateId, String version) {
        return registry.version(templateId, version)
                .orElseThrow(() -> new NoSuchElementException(
                        "No version " + version + " of template " + templateId));
    }

    public List<TemplateCandidate> listCandidates() {
        return candidateRepository.findAll();
    }

    public TemplateCandidate candidate(String candidateId) {
        return candidateRepository.findById(candidateId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + candidateId));
    }

    public record TemplateDetailView(
            TemplateRecord active,
            List<TemplateRecord> versions,
            ExperimentDefinition definition,
            java.util.Map<String, String> activeVersions) {
    }
}
