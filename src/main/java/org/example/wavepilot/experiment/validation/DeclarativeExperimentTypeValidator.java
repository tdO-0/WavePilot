package org.example.wavepilot.experiment.validation;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.ParameterDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates ExperimentSpecs of declarative template types: the experimentTypeId must be a
 * registered definition, custom parameters must match the declared ParameterDefinitions
 * (required, type, bounds, enum), unknown parameters are rejected, and the grid point
 * count is the product of sweep ranges.
 */
public class DeclarativeExperimentTypeValidator implements ExperimentTypeValidator {

    private final ExperimentDefinitionRegistry definitionRegistry;

    public DeclarativeExperimentTypeValidator(ExperimentDefinitionRegistry definitionRegistry) {
        this.definitionRegistry = definitionRegistry;
    }

    @Override
    public ExperimentType experimentType() {
        return null;
    }

    @Override
    public String experimentTypeId() {
        return "declarative";
    }

    @Override
    public boolean supports(ExperimentSpec spec) {
        return spec.experimentTypeId() != null
                && definitionRegistry.byExperimentTypeId(spec.experimentTypeId()).isPresent();
    }

    @Override
    public ValidationResult validate(ExperimentSpec spec) {
        ExperimentDefinition definition = definitionRegistry
                .byExperimentTypeId(spec.experimentTypeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unregistered experimentTypeId: " + spec.experimentTypeId()));
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (spec.outputTypes() == null || spec.outputTypes().isEmpty()) {
            errors.add("outputTypes must not be empty");
        }
        for (ParameterDefinition parameter : definition.parameters()) {
            Object value = spec.customParameters().get(parameter.name());
            if (value == null) {
                if (parameter.required() && parameter.defaultValue() == null) {
                    errors.add("Missing required parameter: " + parameter.name());
                }
                continue;
            }
            validateValue(parameter, value, errors);
        }
        for (String name : spec.customParameters().keySet()) {
            boolean declared = definition.parameters().stream()
                    .anyMatch(parameter -> parameter.name().equals(name));
            if (!declared) {
                errors.add("Unknown parameter for experimentTypeId " + spec.experimentTypeId()
                        + ": " + name);
            }
        }
        return errors.isEmpty()
                ? ValidationResult.success(warnings)
                : ValidationResult.failure(errors, warnings);
    }

    @Override
    public int pointCount(ExperimentSpec spec) {
        ExperimentDefinition definition = definitionRegistry
                .byExperimentTypeId(spec.experimentTypeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unregistered experimentTypeId: " + spec.experimentTypeId()));
        long points = 1;
        for (ParameterDefinition parameter : definition.parameters()) {
            if (!parameter.sweep()) continue;
            // The grid is fully declared by the definition (min/max/step); the runtime
            // value is a validated point inside that grid, not a grid override.
            double min = parameter.min() == null ? Double.NaN : parameter.min();
            double max = parameter.max() == null ? Double.NaN : parameter.max();
            double step = parameter.step() == null ? Double.NaN : parameter.step();
            if (Double.isFinite(min) && Double.isFinite(max) && Double.isFinite(step) && step > 0) {
                long range = (long) Math.floor((max - min) / step) + 1;
                if (range < 1) range = 1;
                points = Math.min(Integer.MAX_VALUE, points * range);
            }
        }
        return (int) points;
    }

    private void validateValue(ParameterDefinition parameter, Object value, List<String> errors) {
        String prefix = "Parameter " + parameter.name() + ": ";
        switch (parameter.type()) {
            case STRING -> {
                if (!(value instanceof String)) errors.add(prefix + "must be a string");
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) errors.add(prefix + "must be a boolean");
            }
            case INTEGER -> {
                if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                    errors.add(prefix + "must be an integer");
                }
                checkBounds(parameter, asDouble(value), errors);
            }
            case NUMBER -> {
                if (!(value instanceof Number)) errors.add(prefix + "must be a number");
                checkBounds(parameter, asDouble(value), errors);
            }
            case ENUM -> {
                String text = String.valueOf(value);
                if (!parameter.enumValues().contains(text)) {
                    errors.add(prefix + "must be one of " + parameter.enumValues());
                }
            }
        }
    }

    private void checkBounds(ParameterDefinition parameter, Double number, List<String> errors) {
        if (number == null) return;
        String prefix = "Parameter " + parameter.name() + ": ";
        if (parameter.min() != null) {
            boolean violated = parameter.minExclusive() ? number <= parameter.min() : number < parameter.min();
            if (violated) errors.add(prefix + "must be >= " + (parameter.minExclusive() ? "(exclusive) " : "") + parameter.min());
        }
        if (parameter.max() != null) {
            boolean violated = parameter.maxExclusive() ? number >= parameter.max() : number > parameter.max();
            if (violated) errors.add(prefix + "must be <= " + (parameter.maxExclusive() ? "(exclusive) " : "") + parameter.max());
        }
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
