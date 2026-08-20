package org.example.wavepilot.template.definition;

/**
 * One declared experiment parameter. {@code sweep=true} marks the parameter as a grid
 * dimension: the point count of the experiment is the product of sweep ranges, and a
 * numeric sweep parameter must carry a positive step.
 */
public record ParameterDefinition(
        String name,
        ParameterType type,
        boolean required,
        Object defaultValue,
        Double min,
        Double max,
        boolean minExclusive,
        boolean maxExclusive,
        java.util.List<String> enumValues,
        boolean sweep,
        Double step,
        String description,
        String unit) {

    public ParameterDefinition {
        enumValues = enumValues == null ? java.util.List.of() : java.util.List.copyOf(enumValues);
    }

    public enum ParameterType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        ENUM
    }
}
