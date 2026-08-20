package org.example.wavepilot.template.definition;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Code- and file-level registry of declarative experiment definitions. Definitions are
 * registered explicitly (built-in YAML at startup, approved templates on publish); nothing
 * is discovered dynamically.
 */
@Component
public class ExperimentDefinitionRegistry {

    private final ConcurrentMap<String, ExperimentDefinition> byTypeId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ExperimentDefinition> byTemplateId = new ConcurrentHashMap<>();

    public void register(ExperimentDefinition definition) {
        byTypeId.put(definition.experimentTypeId(), definition);
        byTemplateId.put(definition.templateId(), definition);
    }

    public Optional<ExperimentDefinition> byExperimentTypeId(String experimentTypeId) {
        return experimentTypeId == null ? Optional.empty()
                : Optional.ofNullable(byTypeId.get(experimentTypeId));
    }

    public Optional<ExperimentDefinition> byTemplateId(String templateId) {
        return templateId == null ? Optional.empty()
                : Optional.ofNullable(byTemplateId.get(templateId));
    }

    public List<ExperimentDefinition> all() {
        return new ArrayList<>(byTemplateId.values());
    }
}
