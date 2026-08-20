package org.example.wavepilot.template.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.template.FileSystemTemplateRepository;
import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.TemplateRegistry;
import org.example.wavepilot.template.TemplateStatus;
import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The only path from candidate to ACTIVE. Approval is an explicit user action carried in
 * {@code approvedBy}; the agent can never call this service. Publication re-checks the
 * candidate (state, security, definition, hashes, version conflicts, algorithmValidated),
 * writes into a temp directory, computes fresh hashes, records a PublicationRecord and
 * atomically moves the files into approved/<templateId>/<version>/ before activating.
 */
@Service
public class TemplatePublishingService {

    private final CandidateTemplateRepository candidateRepository;
    private final CandidateStateMachine stateMachine;
    private final TemplateRegistry registry;
    private final FileSystemTemplateRepository fileRepository;
    private final ExperimentDefinitionRegistry definitionRegistry;
    private final ExperimentDefinitionParser definitionParser;
    private final ExperimentDefinitionValidator definitionValidator;
    private final ObjectMapper objectMapper;

    public TemplatePublishingService(CandidateTemplateRepository candidateRepository,
                                     CandidateStateMachine stateMachine, TemplateRegistry registry,
                                     FileSystemTemplateRepository fileRepository,
                                     ExperimentDefinitionRegistry definitionRegistry,
                                     ExperimentDefinitionParser definitionParser,
                                     ExperimentDefinitionValidator definitionValidator,
                                     ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.stateMachine = stateMachine;
        this.registry = registry;
        this.fileRepository = fileRepository;
        this.definitionRegistry = definitionRegistry;
        this.definitionParser = definitionParser;
        this.definitionValidator = definitionValidator;
        this.objectMapper = objectMapper;
    }

    public TemplateCandidate approveAndPublish(String candidateId, String approvedBy) {
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new PublishingException("Approval requires an explicit approver identity");
        }
        TemplateCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + candidateId));
        if (candidate.status() != TemplateCandidateStatus.REVIEW_REQUIRED
                && candidate.status() != TemplateCandidateStatus.SMOKE_PASSED) {
            throw new IllegalStateException("Candidate " + candidateId
                    + " must be REVIEW_REQUIRED or SMOKE_PASSED before approval, current: "
                    + candidate.status());
        }

        // Re-check everything before publishing.
        if (candidate.securityFindings().stream()
                .anyMatch(finding -> "BLOCKED".equals(finding.severity()))) {
            throw new PublishingException("Candidate has BLOCKED security findings and cannot be published");
        }
        for (TemplateCandidate.CandidateFile file : candidate.files()) {
            if (!file.sha256().equals(sha256(file.content()))) {
                throw new PublishingException("Candidate file hash mismatch (tampered?): " + file.relativePath());
            }
        }
        ExperimentDefinition definition = definitionParser.parse(candidate.definitionYaml());
        List<String> definitionErrors = definitionValidator.validate(definition);
        if (!definitionErrors.isEmpty()) {
            throw new PublishingException("Definition invalid: " + definitionErrors);
        }
        if (definition.algorithm().algorithmValidated()) {
            throw new PublishingException("algorithmValidated must stay false for published templates "
                    + "unless an independent validation reference exists");
        }
        if (registry.version(candidate.templateId(), candidate.version()).isPresent()) {
            throw new PublishingException("Version conflict: " + candidate.templateId() + "/"
                    + candidate.version() + " already exists and is immutable");
        }

        // Approve, then publish atomically.
        stateMachine.transition(candidate.status(), TemplateCandidateStatus.APPROVED);
        TemplateCandidate approved = candidate.withStatus(TemplateCandidateStatus.APPROVED);
        candidateRepository.save(approved);

        List<FileSystemTemplateRepository.TemplateFile> files = new ArrayList<>();
        for (TemplateCandidate.CandidateFile file : candidate.files()) {
            files.add(new FileSystemTemplateRepository.TemplateFile(
                    file.relativePath(), file.content().getBytes(StandardCharsets.UTF_8)));
        }
        // The declarative definition itself must be persisted so a restart can restore the
        // registered definitions from disk instead of losing the declarative chain.
        files.add(new FileSystemTemplateRepository.TemplateFile("experiment-definition.yaml",
                candidate.definitionYaml().getBytes(StandardCharsets.UTF_8)));
        String definitionSha256 = sha256(candidate.definitionYaml());
        String templateSha256 = sha256(files.stream().map(file ->
                file.relativePath() + ":" + HexFormat.of().formatHex(sha256Bytes(
                        new String(file.content(), StandardCharsets.UTF_8)))).toList().toString());
        String previousVersion = registry.active(candidate.templateId())
                .map(TemplateRecord::version).orElse(null);
        String publicationId = "PUB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        PublicationRecord publication = new PublicationRecord(
                publicationId, candidateId, candidate.templateId(), candidate.version(),
                approvedBy, Instant.now(), definitionSha256, templateSha256,
                "SEC-" + candidateId, "SMOKE-" + candidateId, previousVersion,
                candidate.source(), definition.algorithm().algorithmValidated(),
                candidate.realSmokeExecuted(), candidate.smokeReport());
        files.add(new FileSystemTemplateRepository.TemplateFile("publication-record.json",
                toJson(publication)));

        fileRepository.writeApprovedFiles(candidate.templateId(), candidate.version(), files);

        TemplateRecord record = new TemplateRecord(
                candidate.templateId(), candidate.experimentTypeId(), candidate.displayName(),
                candidate.version(), definition.entryPoint(), definition.description(),
                candidate.source(), TemplateStatus.ACTIVE, definition.algorithm().classification(),
                candidate.realSmokeExecuted(), definition.algorithm().algorithmValidated(),
                candidate.createdAt(), Instant.now(), definitionSha256, templateSha256,
                candidate.version(),
                definition.parameters().stream().map(parameter -> parameter.name()).toList(),
                definition.outputs().requiredArtifacts());
        registry.registerApproved(record);
        definitionRegistry.register(definition);

        stateMachine.transition(TemplateCandidateStatus.APPROVED, TemplateCandidateStatus.ACTIVE);
        TemplateCandidate active = approved.withStatus(TemplateCandidateStatus.ACTIVE);
        candidateRepository.save(active);
        return active;
    }

    public TemplateCandidate reject(String candidateId, String reason) {
        TemplateCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + candidateId));
        if (candidate.status() == TemplateCandidateStatus.ACTIVE
                || candidate.status() == TemplateCandidateStatus.REJECTED) {
            throw new IllegalStateException("Candidate cannot be rejected from " + candidate.status());
        }
        stateMachine.transition(candidate.status(), TemplateCandidateStatus.REJECTED);
        TemplateCandidate rejected = candidate.withStatus(TemplateCandidateStatus.REJECTED)
                .withFailure(reason == null || reason.isBlank() ? "Rejected by user" : reason);
        candidateRepository.save(rejected);
        return rejected;
    }

    private byte[] toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (Exception e) {
            throw new PublishingException("Cannot serialize publication record", e);
        }
    }

    private byte[] sha256Bytes(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String sha256(String content) {
        return HexFormat.of().formatHex(sha256Bytes(content));
    }

    public static class PublishingException extends RuntimeException {
        public PublishingException(String message) { super(message); }
        public PublishingException(String message, Throwable cause) { super(message, cause); }
    }
}
