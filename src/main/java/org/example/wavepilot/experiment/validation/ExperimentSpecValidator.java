package org.example.wavepilot.experiment.validation;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for deterministic spec validation. Parameter semantics belong to the
 * type-specific validators; this class only enforces the registered-type whitelist and
 * delegates everything else. Unregistered experiment types are rejected explicitly.
 * Registration is code-level: add a type here plus its validator implementation.
 */
@Component
public class ExperimentSpecValidator {

    private final List<ExperimentTypeValidator> typeValidators;
    private final ExperimentDefinitionRegistry definitionRegistry;

    public ExperimentSpecValidator() {
        this(null);
    }

    @Autowired
    public ExperimentSpecValidator(ExperimentDefinitionRegistry definitionRegistry) {
        this.definitionRegistry = definitionRegistry;
        Map<ExperimentType, ExperimentTypeValidator> registered = new LinkedHashMap<>();
        registered.put(ExperimentType.POLAR_CODE_K_IDENTIFICATION, new PolarCodeKTypeValidator());
        List<ExperimentTypeValidator> validators = new ArrayList<>();
        validators.addAll(registered.values());
        if (definitionRegistry != null) {
            validators.add(new DeclarativeExperimentTypeValidator(definitionRegistry));
        }
        this.typeValidators = List.copyOf(validators);
    }

    public ValidationResult validate(ExperimentSpec spec) {
        if (spec == null) {
            return ValidationResult.failure(List.of("ExperimentSpec must not be null"), List.of());
        }
        for (ExperimentTypeValidator validator : typeValidators) {
            if (validator.supports(spec)) {
                return validator.validate(spec);
            }
        }
        return ValidationResult.failure(
                List.of("Unsupported experiment type: " + spec.experimentType()
                        + (spec.experimentTypeId() != null
                        ? " / experimentTypeId=" + spec.experimentTypeId() : "")
                        + "; registered types: " + registeredTypes()),
                List.of());
    }

    public int calculateErrorRatePointCount(ExperimentSpec spec) {
        for (ExperimentTypeValidator validator : typeValidators) {
            if (validator.supports(spec)) {
                return validator.pointCount(spec);
            }
        }
        throw new IllegalArgumentException("Unsupported experiment type: " + spec.experimentType()
                + (spec.experimentTypeId() != null ? " / " + spec.experimentTypeId() : "")
                + "; registered types: " + registeredTypes());
    }

    private String registeredTypes() {
        return typeValidators.stream()
                .map(validator -> validator.experimentTypeId() != null
                        ? validator.experimentTypeId() : validator.experimentType().name())
                .toList().toString();
    }
}
