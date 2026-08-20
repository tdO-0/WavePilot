package org.example.wavepilot.experiment;

import org.example.wavepilot.experiment.model.GenericExperimentSpec;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ParameterDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the dynamic parameter grid (and hence the experiment point count) from the
 * template's parameter definitions and the actual runtime parameter values. A numeric
 * sweep group (start/end/step) yields {@code floor((end-start)/step)+1} points; other
 * sweep dimensions contribute their value count. The grid never reads fake polar fields —
 * it is driven purely by the definition schema and the concrete values.
 */
@Component
public class ExperimentGridResolver {

    public Grid resolve(ExperimentDefinition definition, GenericExperimentSpec spec) {
        Map<String, Long> dimensionSizes = new LinkedHashMap<>();
        double points = 1;

        // Sweep groups: a *Start/*End/*Step triple from the template definition.
        for (ParameterDefinition parameter : definition.parameters()) {
            if (!parameter.sweep() || !parameter.name().endsWith("Start")) continue;
            String base = parameter.name().substring(0, parameter.name().length() - "Start".length());
            Double start = number(spec.parameter(base + "Start"));
            Double end = number(spec.parameter(base + "End"));
            Double step = number(spec.parameter(base + "Step"));
            if (start == null || end == null) continue;
            double stepValue = step != null && step > 0 ? step : 1.0;
            long size = (long) Math.floor((end - start) / stepValue) + 1;
            size = Math.max(1, size);
            dimensionSizes.put(base, size);
            points *= size;
        }

        // Other sweep dimensions (list-valued or plain numeric sweep params).
        for (ParameterDefinition parameter : definition.parameters()) {
            if (!parameter.sweep() || parameter.name().endsWith("Start")
                    || parameter.name().endsWith("End") || parameter.name().endsWith("Step")) {
                continue;
            }
            Object value = spec.parameter(parameter.name());
            if (value == null) continue;
            long size = valueSize(value);
            dimensionSizes.put(parameter.name(), size);
            points *= Math.max(1, size);
        }

        long total = (long) Math.max(1, points);
        return new Grid(total, Map.copyOf(dimensionSizes));
    }

    private long valueSize(Object value) {
        if (value instanceof Number number) {
            return Math.max(1, (long) Math.round(number.doubleValue()));
        }
        if (value instanceof String text) {
            String[] parts = text.split("[,\\s;]+");
            return Math.max(1, parts.length);
        }
        if (value instanceof java.util.List<?> list) {
            return Math.max(1, list.size());
        }
        return 1;
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    public record Grid(long totalPoints, Map<String, Long> dimensionSizes) { }
}
