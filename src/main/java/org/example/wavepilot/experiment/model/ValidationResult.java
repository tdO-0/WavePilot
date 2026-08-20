package org.example.wavepilot.experiment.model;

import java.util.List;

public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        valid = errors.isEmpty();
    }

    public static ValidationResult success(List<String> warnings) {
        return new ValidationResult(true, List.of(), warnings);
    }

    public static ValidationResult failure(List<String> errors, List<String> warnings) {
        return new ValidationResult(false, errors, warnings);
    }
}
