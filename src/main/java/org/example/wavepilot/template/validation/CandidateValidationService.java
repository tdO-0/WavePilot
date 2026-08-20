package org.example.wavepilot.template.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;
import org.example.wavepilot.template.security.TemplateSecurityScanner;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Static validation chain for a candidate: MATLAB security scan, definition schema,
 * manifest consistency, entry-point contract, parameter/input wiring hints and SHA-256
 * integrity. A candidate with BLOCKED findings or invalid definitions lands in
 * VALIDATION_FAILED; a clean one goes to SMOKE_PENDING.
 */
@Service
public class CandidateValidationService {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");

    private final CandidateTemplateRepository candidateRepository;
    private final TemplateSecurityScanner securityScanner;
    private final ExperimentDefinitionParser definitionParser;
    private final ExperimentDefinitionValidator definitionValidator;
    private final ObjectMapper objectMapper;

    public CandidateValidationService(CandidateTemplateRepository candidateRepository,
                                      TemplateSecurityScanner securityScanner,
                                      ExperimentDefinitionParser definitionParser,
                                      ExperimentDefinitionValidator definitionValidator,
                                      ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.securityScanner = securityScanner;
        this.definitionParser = definitionParser;
        this.definitionValidator = definitionValidator;
        this.objectMapper = objectMapper;
    }

    public TemplateCandidate validate(String candidateId) {
        TemplateCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + candidateId));
        TemplateCandidate working = candidate.withStatus(TemplateCandidateStatus.VALIDATING);
        candidateRepository.save(working);

        List<String> errors = new ArrayList<>();
        TemplateSecurityScanner.ScanResult scan = securityScanner.scan(candidate.files());
        TemplateCandidate withFindings = working.withSecurity(scan.findings());
        if (!scan.passed()) {
            errors.add("Security scan blocked: " + scan.blocked().stream()
                    .map(finding -> finding.ruleId() + "@" + finding.file() + ":" + finding.line())
                    .toList());
        }
        if (!SAFE_ID.matcher(candidate.templateId()).matches()) {
            errors.add("templateId must match " + SAFE_ID.pattern());
        }
        if (!SAFE_ID.matcher(candidate.experimentTypeId()).matches()) {
            errors.add("experimentTypeId must match " + SAFE_ID.pattern());
        }
        try {
            ExperimentDefinition definition = definitionParser.parse(candidate.definitionYaml());
            errors.addAll(definitionValidator.validate(definition));
            if (definition.customExtensionRequired()) {
                errors.add("REQUIRES_CUSTOM_EXTENSION: 声明式系统无法表达该模板的复杂规则");
            }
            if (definition.algorithm().algorithmValidated()) {
                errors.add("Generated candidates must keep algorithmValidated=false");
            }
        } catch (RuntimeException e) {
            errors.add("Definition invalid: " + e.getMessage());
        }
        errors.addAll(validateManifest(candidate));
        errors.addAll(validateIntegrity(candidate));
        checkMatlabInputWiring(candidate, errors);

        if (!errors.isEmpty()) {
            TemplateCandidate failed = withFindings.withStatus(TemplateCandidateStatus.VALIDATION_FAILED)
                    .withFailure(String.join("; ", errors));
            candidateRepository.save(failed);
            return failed;
        }
        TemplateCandidate pending = withFindings.withStatus(TemplateCandidateStatus.SMOKE_PENDING);
        candidateRepository.save(pending);
        return pending;
    }

    private List<String> validateManifest(TemplateCandidate candidate) {
        List<String> errors = new ArrayList<>();
        try {
            JsonNode manifest = objectMapper.readTree(candidate.manifestJson());
            if (manifest.get("templateName") == null
                    || !candidate.templateId().equals(manifest.get("templateName").asText())) {
                errors.add("TEMPLATE_MANIFEST templateName mismatch");
            }
            if (manifest.get("experimentType") == null
                    || !candidate.experimentTypeId().equals(manifest.get("experimentType").asText())) {
                errors.add("TEMPLATE_MANIFEST experimentType mismatch");
            }
            if (manifest.get("algorithmValidated") != null && manifest.get("algorithmValidated").asBoolean()) {
                errors.add("TEMPLATE_MANIFEST algorithmValidated must stay false");
            }
        } catch (Exception e) {
            errors.add("TEMPLATE_MANIFEST.json is invalid: " + e.getMessage());
        }
        return errors;
    }

    private List<String> validateIntegrity(TemplateCandidate candidate) {
        List<String> errors = new ArrayList<>();
        for (TemplateCandidate.CandidateFile file : candidate.files()) {
            String actual = sha256(file.content());
            if (!file.sha256().equals(actual)) {
                errors.add("Candidate file hash mismatch (tampered?): " + file.relativePath());
            }
        }
        return errors;
    }

    private void checkMatlabInputWiring(TemplateCandidate candidate, List<String> errors) {
        boolean readsInput = candidate.files().stream()
                .filter(file -> file.relativePath().endsWith(".m"))
                .anyMatch(file -> file.content().contains("matlab-input.json")
                        || file.content().contains("inputFile"));
        if (!readsInput) {
            errors.add("The MATLAB entry point must read matlab-input.json (fixed input contract)");
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
}
