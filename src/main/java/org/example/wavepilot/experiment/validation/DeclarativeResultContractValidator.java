package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.OutputContractDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enforces the declared OutputContractDefinition of a declarative template: required CSV
 * columns, numeric parsing with NaN/Inf rejection, non-empty rows, per-column bounds,
 * mandatory summary JSON fields and required artifact types.
 */
public class DeclarativeResultContractValidator implements ExperimentResultContractValidator {

    private final ExperimentDefinitionRegistry definitionRegistry;
    private final ObjectMapper objectMapper;

    public DeclarativeResultContractValidator(ExperimentDefinitionRegistry definitionRegistry,
                                              ObjectMapper objectMapper) {
        this.definitionRegistry = definitionRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExperimentType experimentType() {
        return null;
    }

    @Override
    public void validate(ExperimentJob job, Map<ArtifactType, Path> artifacts, List<String> errors) {
        String experimentTypeId = job.getGenericSpec() != null
                ? job.getGenericSpec().experimentTypeId()
                : job.getSpec() == null ? null : job.getSpec().experimentTypeId();
        if (experimentTypeId == null) {
            errors.add("Declarative contract requires an experimentTypeId");
            return;
        }
        ExperimentDefinition definition = definitionRegistry
                .byExperimentTypeId(experimentTypeId).orElse(null);
        if (definition == null) {
            errors.add("No declarative definition is registered for experimentTypeId: "
                    + job.getSpec().experimentTypeId());
            return;
        }
        validate(definition, job, artifacts, errors);
    }

    /** Validates against an explicitly provided definition (e.g. an unpublished candidate). */
    public void validate(ExperimentDefinition definition, ExperimentJob job,
                         Map<ArtifactType, Path> artifacts, List<String> errors) {
        OutputContractDefinition contract = definition.outputs();
        for (String artifact : contract.requiredArtifacts()) {
            try {
                ArtifactType type = ArtifactType.valueOf(artifact);
                if (!artifacts.containsKey(type)) {
                    errors.add("Required artifact is missing: " + artifact);
                }
            } catch (IllegalArgumentException e) {
                errors.add("Unknown required artifact type in definition: " + artifact);
            }
        }
        Path csv = artifacts.get(ArtifactType.ACCURACY_CSV);
        if (csv == null) {
            errors.add("Required artifact is missing: ACCURACY_CSV");
            return;
        }
        validateCsv(contract, csv, errors);
        Path summary = artifacts.get(ArtifactType.SUMMARY_JSON);
        if (summary != null) {
            validateSummary(contract, summary, errors);
        }
    }

    private void validateCsv(OutputContractDefinition contract, Path csv, List<String> errors) {
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                errors.add("accuracy.csv has no header");
                return;
            }
            String[] headers = headerLine.split(",", -1);
            Map<String, Integer> columnIndex = new java.util.HashMap<>();
            for (int index = 0; index < headers.length; index++) {
                columnIndex.put(headers[index].trim(), index);
            }
            for (String required : contract.requiredColumns()) {
                if (!columnIndex.containsKey(required)) {
                    errors.add("accuracy.csv is missing required column: " + required);
                }
            }
            boolean rejectNonFinite = contract.rejectNonFinite();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                for (String numeric : contract.numericColumns()) {
                    Integer index = columnIndex.get(numeric);
                    if (index == null || index >= fields.length) {
                        errors.add("accuracy.csv row " + lineNumber + " is missing numeric column " + numeric);
                        continue;
                    }
                    String raw = fields[index].trim();
                    if (raw.isEmpty()) {
                        errors.add("accuracy.csv row " + lineNumber + " has an empty value for " + numeric);
                        continue;
                    }
                    try {
                        double value = Double.parseDouble(raw);
                        if (rejectNonFinite && !Double.isFinite(value)) {
                            errors.add("accuracy.csv row " + lineNumber + " column " + numeric
                                    + " must be finite (NaN/Inf rejected)");
                            continue;
                        }
                        List<Double> bounds = contract.columnBounds().get(numeric);
                        if (bounds != null && bounds.size() == 2
                                && (value < bounds.get(0) || value > bounds.get(1))) {
                            errors.add("accuracy.csv row " + lineNumber + " column " + numeric
                                    + " is outside [" + bounds.get(0) + ", " + bounds.get(1) + "]");
                        }
                    } catch (NumberFormatException e) {
                        errors.add("accuracy.csv row " + lineNumber + " column " + numeric
                                + " is not numeric: " + raw);
                    }
                }
            }
        } catch (IOException e) {
            errors.add("Cannot parse accuracy.csv: " + e.getMessage());
        }
    }

    private void validateSummary(OutputContractDefinition contract, Path summary, List<String> errors) {
        try {
            JsonNode node = objectMapper.readTree(summary.toFile());
            for (String required : contract.jsonRequiredFields()) {
                if (node.get(required) == null) {
                    errors.add("summary.json is missing required field: " + required);
                }
            }
        } catch (IOException e) {
            errors.add("Cannot parse summary.json: " + e.getMessage());
        }
    }
}
