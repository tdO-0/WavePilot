package org.example.wavepilot.template.definition;

import java.util.List;
import java.util.Map;

/**
 * Declared result contract of a template: CSV columns (mandatory, numeric, non-empty,
 * NaN/Inf rejection, per-column bounds), mandatory summary JSON fields, and which artifact
 * types a successful run must produce.
 */
public record OutputContractDefinition(
        String csvFile,
        List<String> requiredColumns,
        List<String> numericColumns,
        boolean rejectNonFinite,
        Map<String, List<Double>> columnBounds,
        List<String> jsonRequiredFields,
        List<String> requiredArtifacts) {

    public OutputContractDefinition {
        requiredColumns = requiredColumns == null ? List.of() : List.copyOf(requiredColumns);
        numericColumns = numericColumns == null ? List.of() : List.copyOf(numericColumns);
        columnBounds = columnBounds == null ? Map.of() : Map.copyOf(columnBounds);
        jsonRequiredFields = jsonRequiredFields == null ? List.of() : List.copyOf(jsonRequiredFields);
        requiredArtifacts = requiredArtifacts == null ? List.of() : List.copyOf(requiredArtifacts);
    }
}
