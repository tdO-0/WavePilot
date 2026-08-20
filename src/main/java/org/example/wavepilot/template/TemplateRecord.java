package org.example.wavepilot.template;

import java.time.Instant;
import java.util.List;

/**
 * One version of a formal template as exposed by the registry. Never contains absolute
 * paths; {@code activeVersion} points at the currently executable version of a templateId.
 */
public record TemplateRecord(
        String templateId,
        String experimentTypeId,
        String displayName,
        String version,
        String entryPoint,
        String description,
        TemplateSource source,
        TemplateStatus status,
        String classification,
        boolean operationalValidated,
        boolean algorithmValidated,
        Instant createdAt,
        Instant publishedAt,
        String definitionSha256,
        String templateSha256,
        String activeVersion,
        List<String> supportedParameters,
        List<String> outputArtifacts) {

    public TemplateRecord {
        supportedParameters = supportedParameters == null ? List.of() : List.copyOf(supportedParameters);
        outputArtifacts = outputArtifacts == null ? List.of() : List.copyOf(outputArtifacts);
    }
}
