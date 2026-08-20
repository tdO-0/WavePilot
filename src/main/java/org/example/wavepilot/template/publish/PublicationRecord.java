package org.example.wavepilot.template.publish;

import org.example.wavepilot.template.TemplateSource;

import java.time.Instant;

/** Immutable publication record written next to the approved template files. */
public record PublicationRecord(
        String publicationId,
        String candidateId,
        String templateId,
        String version,
        String approvedBy,
        Instant approvedAt,
        String definitionSha256,
        String templateSha256,
        String securityReportId,
        String smokeReportId,
        String previousVersion,
        TemplateSource source,
        boolean algorithmValidated,
        boolean realSmokeExecuted,
        String smokeReport) {
}
