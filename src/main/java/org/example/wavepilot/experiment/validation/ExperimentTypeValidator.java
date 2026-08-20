package org.example.wavepilot.experiment.validation;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.ValidationResult;

/**
 * Type-specific validation contract. Each registered experiment type owns its parameter
 * semantics (what fields are legal, which ranges are allowed) and its parameter-grid point
 * count. Registration is code-level: every new type must implement this interface and be
 * registered in {@link ExperimentSpecValidator}, nothing is loaded dynamically.
 *
 * Built-in types dispatch on the legacy {@link ExperimentType} enum; declarative template
 * types override {@link #experimentTypeId()} and {@link #supports(ExperimentSpec)} so the
 * enum never blocks a registered declarative type.
 */
public interface ExperimentTypeValidator {

    ExperimentType experimentType();

    /** The declarative experimentTypeId this validator serves, or null for built-in types. */
    default String experimentTypeId() {
        return null;
    }

    default boolean supports(ExperimentSpec spec) {
        if (spec.experimentTypeId() != null) {
            return spec.experimentTypeId().equals(experimentTypeId());
        }
        return spec.experimentType() == experimentType();
    }

    ValidationResult validate(ExperimentSpec spec);

    /** Number of parameter-grid points one codeLength/frame-length sweep produces. */
    int pointCount(ExperimentSpec spec);
}
