package org.example.wavepilot.template.definition;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Schema-level validation of a parsed definition: identifier format, parameter semantics,
 * output contract consistency, metric/replay column references and the algorithm boundary.
 */
@Component
public class ExperimentDefinitionValidator {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");

    public List<String> validate(ExperimentDefinition definition) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            errors.add("ExperimentDefinition must not be null");
            return errors;
        }
        requireSafeId(errors, "templateId", definition.templateId());
        requireSafeId(errors, "experimentTypeId", definition.experimentTypeId());
        requireText(errors, "displayName", definition.displayName());
        requireText(errors, "version", definition.version());
        requireText(errors, "entryPoint", definition.entryPoint());

        Set<String> parameterNames = new HashSet<>();
        for (ParameterDefinition parameter : definition.parameters()) {
            if (!parameterNames.add(parameter.name())) {
                errors.add("Duplicate parameter name: " + parameter.name());
            }
            if (parameter.type() == ParameterDefinition.ParameterType.ENUM
                    && parameter.enumValues().isEmpty()) {
                errors.add("ENUM parameter " + parameter.name() + " must declare enumValues");
            }
            if (parameter.min() != null && parameter.max() != null
                    && parameter.min() > parameter.max()) {
                errors.add("Parameter " + parameter.name() + " min must not exceed max");
            }
            if (parameter.sweep()) {
                if (parameter.type() != ParameterDefinition.ParameterType.INTEGER
                        && parameter.type() != ParameterDefinition.ParameterType.NUMBER) {
                    errors.add("Sweep parameter " + parameter.name() + " must be numeric");
                }
                if (parameter.step() == null || parameter.step() <= 0) {
                    errors.add("Sweep parameter " + parameter.name() + " must declare a positive step");
                }
                if (parameter.min() == null || parameter.max() == null) {
                    errors.add("Sweep parameter " + parameter.name() + " must declare min and max");
                }
            }
        }

        OutputContractDefinition outputs = definition.outputs();
        if (outputs == null) {
            errors.add("outputs contract is required");
            return errors;
        }
        if (outputs.requiredColumns().isEmpty()) {
            errors.add("outputs.requiredColumns must not be empty");
        }
        Set<String> columns = new HashSet<>(outputs.requiredColumns());
        for (String numeric : outputs.numericColumns()) {
            if (!columns.contains(numeric)) {
                errors.add("numericColumns entry must be a required column: " + numeric);
            }
        }
        for (String column : outputs.columnBounds().keySet()) {
            if (!columns.contains(column)) {
                errors.add("columnBounds entry must be a required column: " + column);
            }
            List<Double> range = outputs.columnBounds().get(column);
            if (range == null || range.size() != 2 || range.get(0) > range.get(1)) {
                errors.add("columnBounds for " + column + " must be [min, max]");
            }
        }

        for (MetricDefinition metric : definition.metrics()) {
            if (!columns.contains(metric.sourceColumn())) {
                errors.add("Metric " + metric.metricName() + " sourceColumn is not a required column: "
                        + metric.sourceColumn());
            }
        }
        for (ReplayMetricDefinition replay : definition.replay()) {
            if (!columns.contains(replay.comparisonColumn())) {
                errors.add("Replay comparisonColumn is not a required column: "
                        + replay.comparisonColumn());
            }
            if (replay.maxAbsoluteTolerance() != null && replay.maxAbsoluteTolerance() < 0) {
                errors.add("Replay tolerance must be non-negative for " + replay.comparisonColumn());
            }
        }

        AlgorithmMetadata algorithm = definition.algorithm();
        if (algorithm == null) {
            errors.add("algorithm metadata is required");
        } else {
            requireText(errors, "algorithm.name", algorithm.name());
            requireText(errors, "algorithm.version", algorithm.version());
            requireText(errors, "algorithm.classification", algorithm.classification());
            if (algorithm.algorithmValidated() && algorithm.validationReference() == null) {
                errors.add("algorithmValidated=true requires an independent validationReference");
            }
        }
        if (definition.customExtensionRequired()) {
            errors.add("REQUIRES_CUSTOM_EXTENSION: this definition needs a Java extension and "
                    + "cannot be executed through the declarative path");
        }
        return errors;
    }

    private void requireSafeId(List<String> errors, String field, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            errors.add(field + " must match " + SAFE_ID.pattern() + ": " + value);
        }
    }

    private void requireText(List<String> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(field + " must not be blank");
        }
    }
}
