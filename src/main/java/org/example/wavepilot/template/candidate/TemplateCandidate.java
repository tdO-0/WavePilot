package org.example.wavepilot.template.candidate;

import org.example.wavepilot.template.TemplateSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One generated candidate template package. Files are stored under
 * {@code candidates/<candidateId>/}; the record carries metadata, validation state, the
 * security report and the smoke report. Never exposes absolute paths.
 */
public record TemplateCandidate(
        String candidateId,
        String templateId,
        String experimentTypeId,
        String displayName,
        String version,
        TemplateCandidateStatus status,
        TemplateSource source,
        String request,
        String definitionYaml,
        String manifestJson,
        String generationNotes,
        List<String> assumptions,
        List<String> unresolvedQuestions,
        List<CandidateFile> files,
        List<SecurityFinding> securityFindings,
        String smokeReport,
        boolean realSmokeExecuted,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public TemplateCandidate {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
        files = files == null ? List.of() : List.copyOf(files);
        securityFindings = securityFindings == null ? List.of() : List.copyOf(securityFindings);
    }

    public TemplateCandidate withStatus(TemplateCandidateStatus next) {
        return new TemplateCandidate(candidateId, templateId, experimentTypeId, displayName, version,
                next, source, request, definitionYaml, manifestJson, generationNotes, assumptions,
                unresolvedQuestions, files, securityFindings, smokeReport, realSmokeExecuted,
                failureReason, createdAt, Instant.now());
    }

    public TemplateCandidate withSecurity(List<SecurityFinding> findings) {
        return new TemplateCandidate(candidateId, templateId, experimentTypeId, displayName, version,
                status, source, request, definitionYaml, manifestJson, generationNotes, assumptions,
                unresolvedQuestions, files, findings, smokeReport, realSmokeExecuted,
                failureReason, createdAt, Instant.now());
    }

    public TemplateCandidate withSmoke(String report, boolean realExecuted) {
        return new TemplateCandidate(candidateId, templateId, experimentTypeId, displayName, version,
                status, source, request, definitionYaml, manifestJson, generationNotes, assumptions,
                unresolvedQuestions, files, securityFindings, report, realExecuted,
                failureReason, createdAt, Instant.now());
    }

    public TemplateCandidate withFailure(String reason) {
        return new TemplateCandidate(candidateId, templateId, experimentTypeId, displayName, version,
                status, source, request, definitionYaml, manifestJson, generationNotes, assumptions,
                unresolvedQuestions, files, securityFindings, smokeReport, realSmokeExecuted,
                reason, createdAt, Instant.now());
    }

    public record CandidateFile(String relativePath, String content, String sha256) { }

    public record SecurityFinding(String ruleId, String severity, String file, Integer line,
                                  String message, String evidence) { }
}
