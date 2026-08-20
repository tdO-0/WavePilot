package org.example.wavepilot.template.definition;

import java.util.List;

/**
 * Optional semantic capability metadata of a template, used by {@code TemplateResolver} to
 * match a resolved {@code ExperimentIntent} to a template. All fields are optional; old
 * templates without capabilities simply match by id/type/name aliases.
 */
public record TemplateCapabilities(
        String experimentFamily,
        String objective,
        String modulation,
        String coding,
        String channel,
        List<String> tags,
        List<String> aliases) {

    public TemplateCapabilities {
        tags = tags == null ? List.of() : List.copyOf(tags);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public static TemplateCapabilities empty() {
        return new TemplateCapabilities(null, null, null, null, null, List.of(), List.of());
    }
}
