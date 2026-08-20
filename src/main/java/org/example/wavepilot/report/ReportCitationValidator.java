package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReportCitationValidator {

    private static final double TOLERANCE = 1.0e-9;
    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;

    public ReportCitationValidator(ArtifactRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(String jobId, ExperimentReportData data) {
        List<String> errors = new ArrayList<>();
        if (data == null) return ValidationResult.failure(List.of("ExperimentReportData must not be null"), List.of());
        if (!jobId.equals(data.jobId())) errors.add("Report data belongs to another job");
        Map<String, ArtifactCitation> citations = new HashMap<>();
        for (ArtifactCitation citation : data.citations()) {
            if (citation == null || citation.citationId() == null || citation.citationId().isBlank()) {
                errors.add("Citation has no citationId");
                continue;
            }
            if (citations.put(citation.citationId(), citation) != null) {
                errors.add("Duplicate citationId: " + citation.citationId());
            }
            validateCitation(jobId, citation, errors);
        }
        for (ReportConclusion conclusion : data.conclusions()) {
            validateConclusion(conclusion, citations, errors);
        }
        return errors.isEmpty() ? ValidationResult.success(List.of())
                : ValidationResult.failure(errors, List.of());
    }

    /** Validates the generic (declarative) result model's citations and conclusions. */
    public ValidationResult validate(ExperimentResultData data) {
        List<String> errors = new ArrayList<>();
        if (data == null) return ValidationResult.failure(List.of("ExperimentResultData must not be null"), List.of());
        Map<String, ArtifactCitation> citations = new HashMap<>();
        for (ArtifactCitation citation : data.citations()) {
            if (citation == null || citation.citationId() == null || citation.citationId().isBlank()) {
                errors.add("Citation has no citationId");
                continue;
            }
            if (citations.put(citation.citationId(), citation) != null) {
                errors.add("Duplicate citationId: " + citation.citationId());
            }
            validateCitation(data.jobId(), citation, errors);
        }
        for (ReportConclusion conclusion : data.conclusions()) {
            validateConclusion(conclusion, citations, errors);
        }
        return errors.isEmpty() ? ValidationResult.success(List.of())
                : ValidationResult.failure(errors, List.of());
    }

    /** Compatibility check retained for the earlier Phase 2 placeholder contract. */
    public ValidationResult validate(ExperimentReport report) {
        List<String> errors = new ArrayList<>();
        if (report == null) return ValidationResult.failure(List.of("ExperimentReport must not be null"), List.of());
        for (int index = 0; index < report.conclusions().size(); index++) {
            ExperimentReport.Conclusion conclusion = report.conclusions().get(index);
            if (conclusion == null || conclusion.conclusion() == null || conclusion.conclusion().isBlank()) {
                errors.add("Conclusion " + index + " has no text");
                continue;
            }
            if (conclusion.value() != null) {
                if (conclusion.artifactId() == null || conclusion.artifactId().isBlank())
                    errors.add("Numeric conclusion " + index + " has no artifactId");
                if ((conclusion.fieldName() == null || conclusion.fieldName().isBlank())
                        && (conclusion.rowReference() == null || conclusion.rowReference().isBlank()))
                    errors.add("Numeric conclusion " + index + " has no fieldName or rowReference");
            }
        }
        return errors.isEmpty() ? ValidationResult.success(List.of())
                : ValidationResult.failure(errors, List.of());
    }

    private void validateCitation(String jobId, ArtifactCitation citation, List<String> errors) {
        ArtifactRecord artifact = registry.findById(citation.artifactId()).orElse(null);
        if (artifact == null) {
            errors.add("Citation " + citation.citationId() + " references a missing artifact");
            return;
        }
        if (!jobId.equals(citation.jobId()) || !jobId.equals(artifact.jobId())) {
            errors.add("Citation " + citation.citationId() + " is a cross-job reference");
            return;
        }
        if (citation.artifactType() != artifact.type())
            errors.add("Citation " + citation.citationId() + " artifactType does not match registry");
        if (!artifact.validated())
            errors.add("Citation " + citation.citationId() + " references an artifact not accepted by ResultValidator");
        if (!artifact.sha256().equals(citation.artifactSha256()))
            errors.add("Citation " + citation.citationId() + " SHA-256 does not match ArtifactRecord");
        try {
            if (!registry.verify(artifact.artifactId())) {
                errors.add("Citation " + citation.citationId() + " artifact content hash has changed");
                return;
            }
            Object original = readOriginalValue(artifact, citation);
            if (!sameValue(original, citation.value()))
                errors.add("Citation " + citation.citationId() + " value does not match the source artifact");
        } catch (RuntimeException | IOException e) {
            errors.add("Citation " + citation.citationId() + " is invalid: " + e.getMessage());
        }
    }

    private Object readOriginalValue(ArtifactRecord artifact, ArtifactCitation citation) throws IOException {
        if (artifact.type() == ArtifactType.ACCURACY_CSV) return readCsvValue(artifact, citation);
        if (artifact.type() == ArtifactType.SUMMARY_JSON
                || artifact.type() == ArtifactType.EXPERIMENT_SPEC
                || artifact.type() == ArtifactType.EXPERIMENT_PLAN) {
            JsonNode root = objectMapper.readTree(registry.resolveVerified(artifact.artifactId()).toFile());
            // Dot-separated paths resolve nested fields (e.g. "parameters.ebNoStart" on the
            // generic spec artifact); legacy flat fields keep working via a plain lookup.
            JsonNode value = root.at("/" + citation.fieldName().replace('.', '/'));
            if (value.isMissingNode()) value = root.get(citation.fieldName());
            if (value == null || value.isMissingNode()) {
                throw new IllegalArgumentException("fieldName does not exist: " + citation.fieldName());
            }
            return objectMapper.convertValue(value, Object.class);
        }
        throw new IllegalArgumentException("Artifact type is not a structured citation source");
    }

    private Object readCsvValue(ArtifactRecord artifact, ArtifactCitation citation) throws IOException {
        int target;
        try {
            target = Integer.parseInt(citation.rowReference());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("rowReference must be a positive CSV data row");
        }
        if (target < 1) throw new IllegalArgumentException("rowReference must be a positive CSV data row");
        try (BufferedReader reader = Files.newBufferedReader(registry.resolveVerified(artifact.artifactId()),
                StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IllegalArgumentException("CSV header is missing");
            String[] headers = headerLine.split(",", -1);
            int fieldIndex = -1;
            for (int index = 0; index < headers.length; index++) {
                if (headers[index].equals(citation.fieldName())) fieldIndex = index;
            }
            if (fieldIndex < 0) throw new IllegalArgumentException("fieldName does not exist: " + citation.fieldName());
            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                row++;
                if (row == target) {
                    String[] fields = line.split(",", -1);
                    if (fieldIndex >= fields.length) throw new IllegalArgumentException("CSV field is missing in row");
                    String raw = fields[fieldIndex];
                    try { return Double.parseDouble(raw); }
                    catch (NumberFormatException ignored) { return unquote(raw); }
                }
            }
        }
        throw new IllegalArgumentException("rowReference does not exist: " + target);
    }

    private void validateConclusion(ReportConclusion conclusion,
                                    Map<String, ArtifactCitation> citations, List<String> errors) {
        if (conclusion == null || conclusion.text() == null || conclusion.text().isBlank()) {
            errors.add("Report conclusion has no text");
            return;
        }
        if (conclusion.metricValue() != null && conclusion.citationIds().isEmpty()) {
            errors.add("Numeric conclusion " + conclusion.conclusionId() + " has no citation");
            return;
        }
        boolean grounded = conclusion.metricValue() == null;
        for (String citationId : conclusion.citationIds()) {
            ArtifactCitation citation = citations.get(citationId);
            if (citation == null) {
                errors.add("Conclusion " + conclusion.conclusionId() + " references missing citation " + citationId);
            } else if (conclusion.metricValue() != null && sameValue(citation.value(), conclusion.metricValue())) {
                grounded = true;
            }
        }
        if (!grounded) errors.add("Conclusion " + conclusion.conclusionId() + " metric has no matching source value");
        if (conclusion.citationStatus() != CitationStatus.VERIFIED)
            errors.add("Conclusion " + conclusion.conclusionId() + " citationStatus is not VERIFIED");
    }

    private boolean sameValue(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return Double.isFinite(l.doubleValue()) && Double.isFinite(r.doubleValue())
                    && Math.abs(l.doubleValue() - r.doubleValue()) <= TOLERANCE;
        }
        return objectMapper.valueToTree(left).equals(objectMapper.valueToTree(right));
    }

    private String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
