package org.example.wavepilot.template;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime template root, configured and never hardcoded. Defaults to
 * {@code data/wavepilot/templates} relative to the working directory.
 */
@Component
public class TemplateRootProperties {

    private final String root;

    public TemplateRootProperties(@Value("${wavepilot.templates.root:data/wavepilot/templates}") String root) {
        this.root = root;
    }

    public String root() {
        return root;
    }
}
