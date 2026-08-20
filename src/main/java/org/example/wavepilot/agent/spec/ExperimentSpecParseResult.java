package org.example.wavepilot.agent.spec;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ValidationResult;

import java.util.List;

public record ExperimentSpecParseResult(
        ExperimentSpecParseStatus parseStatus,
        ExperimentSpec experimentSpec,
        List<String> missingFields,
        List<String> clarificationQuestions,
        ValidationResult validationResult,
        List<String> warnings,
        List<String> defaultedFields) {

    public ExperimentSpecParseResult {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        clarificationQuestions = clarificationQuestions == null ? List.of() : List.copyOf(clarificationQuestions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        defaultedFields = defaultedFields == null ? List.of() : List.copyOf(defaultedFields);
    }
}
