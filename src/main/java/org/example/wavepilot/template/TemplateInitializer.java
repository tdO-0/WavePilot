package org.example.wavepilot.template;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers the classpath built-in templates into the formal registry at startup. If the
 * filesystem registry already holds the same templateId/version (e.g. after a restart),
 * registration is skipped so metadata is never duplicated or overwritten.
 */
@Component
public class TemplateInitializer {

    private final TemplateRegistry registry;
    private final BuiltInTemplateProvider provider;

    public TemplateInitializer(TemplateRegistry registry, BuiltInTemplateProvider provider) {
        this.registry = registry;
        this.provider = provider;
    }

    @PostConstruct
    public void registerBuiltInTemplates() {
        for (TemplateRecord record : provider.builtInRecords()) {
            if (registry.version(record.templateId(), record.version()).isEmpty()) {
                registry.registerApproved(record);
            }
        }
    }
}
