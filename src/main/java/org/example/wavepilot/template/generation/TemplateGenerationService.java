package org.example.wavepilot.template.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.TemplateRegistry;
import org.example.wavepilot.template.TemplateSource;
import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Turns a natural-language request into a validated candidate template package. The model
 * only produces structured content; this service normalizes paths, enforces limits, parses
 * and validates the definition, checks manifest consistency, computes SHA-256s and writes
 * the package under candidates/<candidateId>/. The candidate can never reach the formal
 * registry by itself.
 */
@Service
public class TemplateGenerationService {

    private static final int MAX_FILES = 50;
    private static final int MAX_SINGLE_FILE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 5 * 1024 * 1024;

    private final TemplateGenerationModel model;
    private final CandidateTemplateRepository candidateRepository;
    private final CandidateStateMachine stateMachine;
    private final ExperimentDefinitionParser definitionParser;
    private final ExperimentDefinitionValidator definitionValidator;
    private final ExperimentDefinitionRegistry definitionRegistry;
    private final TemplateRegistry templateRegistry;
    private final ObjectMapper objectMapper;

    public TemplateGenerationService(TemplateGenerationModel model,
                                     CandidateTemplateRepository candidateRepository,
                                     CandidateStateMachine stateMachine,
                                     ExperimentDefinitionParser definitionParser,
                                     ExperimentDefinitionValidator definitionValidator,
                                     ExperimentDefinitionRegistry definitionRegistry,
                                     ObjectMapper objectMapper) {
        this(model, candidateRepository, stateMachine, definitionParser, definitionValidator,
                definitionRegistry, null, objectMapper);
    }

    @Autowired
    public TemplateGenerationService(TemplateGenerationModel model,
                                     CandidateTemplateRepository candidateRepository,
                                     CandidateStateMachine stateMachine,
                                     ExperimentDefinitionParser definitionParser,
                                     ExperimentDefinitionValidator definitionValidator,
                                     ExperimentDefinitionRegistry definitionRegistry,
                                     TemplateRegistry templateRegistry,
                                     ObjectMapper objectMapper) {
        this.model = model;
        this.candidateRepository = candidateRepository;
        this.stateMachine = stateMachine;
        this.definitionParser = definitionParser;
        this.definitionValidator = definitionValidator;
        this.definitionRegistry = definitionRegistry;
        this.templateRegistry = templateRegistry;
        this.objectMapper = objectMapper;
    }

    public TemplateCandidate generate(String request) {
        if (request == null || request.isBlank()) {
            throw new TemplateGenerationException("A template request is required");
        }
        TemplateGenerationModel.TemplateGenerationResult result = model.generate(request);
        return assemble(result, request);
    }

    /** Schema-driven generation from a resolved experiment intent. */
    public TemplateCandidate generate(org.example.wavepilot.intent.ExperimentIntent intent, String userRequest) {
        if (intent == null || intent.needsClarification()) {
            throw new TemplateGenerationException(
                    "Experiment intent is incomplete; clarify before designing a template");
        }
        TemplateGenerationModel.TemplateGenerationResult result = model.generate(
                new TemplateGenerationModel.ExperimentTemplateDesignRequest(
                        intent, userRequest, intent.requestedOutputs(),
                        intent.suppliedParameters().keySet().stream().toList(), List.of()));
        return assemble(result, userRequest);
    }

    private TemplateCandidate assemble(TemplateGenerationModel.TemplateGenerationResult result,
                                       String request) {
        validateResultPaths(result);
        enforceLimits(result);

        // If the templateId already has published versions, auto-bump to the next patch
        // version so a new candidate can be published without a version conflict.
        String version = result.version();
        String definitionYaml = result.definitionYaml();
        String manifestJson = result.manifestJson();
        String notes = result.generationNotes();
        if (templateRegistry != null && !templateRegistry.versions(result.templateId()).isEmpty()) {
            version = nextVersion(templateRegistry.versions(result.templateId()));
            definitionYaml = replaceVersionField(definitionYaml, version);
            manifestJson = replaceTemplateVersion(manifestJson, version);
            notes = (notes == null ? "" : notes + "；")
                    + "模板已存在旧版本，版本自动递增为 " + version;
        }

        String candidateId = "CAND-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        List<TemplateCandidate.CandidateFile> files = new ArrayList<>();
        for (TemplateGenerationModel.GeneratedFile file : result.files()) {
            files.add(new TemplateCandidate.CandidateFile(file.relativePath(), file.content(),
                    sha256(file.content())));
        }

        List<String> validationErrors = new ArrayList<>();
        ExperimentDefinition definition = null;
        try {
            definition = definitionParser.parse(definitionYaml);
            validationErrors.addAll(definitionValidator.validate(definition));
        } catch (RuntimeException e) {
            validationErrors.add("Definition invalid: " + e.getMessage());
        }
        validationErrors.addAll(validateManifest(manifestJson, result, files));

        TemplateCandidate candidate = new TemplateCandidate(
                candidateId, result.templateId(), result.experimentTypeId(), result.displayName(),
                version, TemplateCandidateStatus.DRAFT, TemplateSource.AGENT_GENERATED,
                request, definitionYaml, manifestJson, notes,
                result.assumptions(), result.unresolvedQuestions(), files, List.of(), null,
                false, null, Instant.now(), Instant.now());
        stateMachine.transition(TemplateCandidateStatus.DRAFT, TemplateCandidateStatus.GENERATED);
        candidate = candidate.withStatus(TemplateCandidateStatus.GENERATED);
        candidate = candidate.withStatus(TemplateCandidateStatus.VALIDATING);
        if (!validationErrors.isEmpty()) {
            candidate = candidate.withStatus(TemplateCandidateStatus.VALIDATION_FAILED)
                    .withFailure(String.join("; ", validationErrors));
        } else {
            candidate = candidate.withStatus(TemplateCandidateStatus.SMOKE_PENDING);
            if (definition != null && definition.customExtensionRequired()) {
                candidate = candidate.withStatus(TemplateCandidateStatus.REQUIRES_CUSTOM_EXTENSION)
                        .withFailure("REQUIRES_CUSTOM_EXTENSION: 声明式系统无法表达该模板的复杂规则");
            }
        }
        candidateRepository.save(candidate);
        return candidate;
    }

    /** Next patch version above the highest existing version, e.g. 1.0.0 -> 1.0.1. */
    private String nextVersion(List<TemplateRecord> existing) {
        int major = 0;
        int minor = 0;
        int patch = 0;
        for (TemplateRecord record : existing) {
            int[] parsed = parseVersion(record.version());
            if (parsed == null) continue;
            if (parsed[0] > major || (parsed[0] == major && parsed[1] > minor)
                    || (parsed[0] == major && parsed[1] == minor && parsed[2] > patch)) {
                major = parsed[0];
                minor = parsed[1];
                patch = parsed[2];
            }
        }
        return major + "." + minor + "." + (patch + 1);
    }

    private int[] parseVersion(String version) {
        if (version == null) return null;
        String[] parts = version.split("\\.");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String replaceVersionField(String yaml, String newVersion) {
        return yaml.replaceAll("(?m)^version: .*$", "version: " + newVersion);
    }

    private String replaceTemplateVersion(String manifest, String newVersion) {
        return manifest.replaceAll("\"templateVersion\"\\s*:\\s*\"[^\"]*\"",
                "\"templateVersion\": \"" + newVersion + "\"");
    }

    private List<String> validateManifest(String manifestJson,
                                          TemplateGenerationModel.TemplateGenerationResult result,
                                          List<TemplateCandidate.CandidateFile> files) {
        List<String> errors = new ArrayList<>();
        try {
            JsonNode manifest = objectMapper.readTree(manifestJson);
            if (manifest.get("templateName") == null
                    || !result.templateId().equals(manifest.get("templateName").asText())) {
                errors.add("TEMPLATE_MANIFEST templateName does not match the definition templateId");
            }
            if (manifest.get("experimentType") == null
                    || !result.experimentTypeId().equals(manifest.get("experimentType").asText())) {
                errors.add("TEMPLATE_MANIFEST experimentType does not match the definition experimentTypeId");
            }
            if (manifest.get("algorithmValidated") != null && manifest.get("algorithmValidated").asBoolean()) {
                errors.add("TEMPLATE_MANIFEST algorithmValidated must stay false for generated candidates");
            }
        } catch (Exception e) {
            errors.add("TEMPLATE_MANIFEST.json is not valid JSON: " + e.getMessage());
        }
        boolean hasEntryPoint = files.stream().anyMatch(file ->
                "matlab/run_experiment.m".equals(file.relativePath())
                        || "run_experiment.m".equals(file.relativePath()));
        if (!hasEntryPoint) {
            errors.add("The fixed entry point matlab/run_experiment.m is missing");
        }
        return errors;
    }

    private void validateResultPaths(TemplateGenerationModel.TemplateGenerationResult result) {
        for (TemplateGenerationModel.GeneratedFile file : result.files()) {
            String relative = file.relativePath();
            if (relative == null || relative.isBlank()) {
                throw new TemplateGenerationException("A generated file has no relativePath");
            }
            if (relative.startsWith("/") || relative.matches("^[A-Za-z]:.*") || relative.contains("..")
                    || relative.contains("\\")) {
                throw new TemplateGenerationException("Unsafe generated file path: " + relative);
            }
            java.nio.file.Path normalized = java.nio.file.Path.of(relative).normalize();
            if (!normalized.toString().replace('\\', '/').equals(relative)) {
                throw new TemplateGenerationException("Unnormalized generated file path: " + relative);
            }
        }
    }

    private void enforceLimits(TemplateGenerationModel.TemplateGenerationResult result) {
        if (result.files().size() > MAX_FILES) {
            throw new TemplateGenerationException("Too many generated files: " + result.files().size()
                    + " (max " + MAX_FILES + ")");
        }
        long total = 0;
        for (TemplateGenerationModel.GeneratedFile file : result.files()) {
            long bytes = file.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_SINGLE_FILE_BYTES) {
                throw new TemplateGenerationException("Generated file too large: " + file.relativePath());
            }
            total += bytes;
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new TemplateGenerationException("Generated package too large: " + total + " bytes");
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static class TemplateGenerationException extends RuntimeException {
        public TemplateGenerationException(String message) { super(message); }
    }
}
