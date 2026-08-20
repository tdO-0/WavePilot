package org.example.wavepilot.template;

import org.example.wavepilot.intent.ExperimentIntent;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ParameterDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schema-authoritative parameter extraction. The template's {@code ExperimentDefinition}
 * parameters are the ONLY source of truth: values come from the resolved intent's supplied
 * parameters or safe defaults, and only genuinely missing required parameters become
 * clarifying questions. Parameters not present in the schema are never invented.
 */
@Component
public class TemplateParameterExtractor {

    public Extraction extract(ExperimentDefinition definition, ExperimentIntent intent,
                              Map<String, Object> knownValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> missingRequired = new ArrayList<>();
        List<String> invalidValues = new ArrayList<>();
        List<String> clarifyingQuestions = new ArrayList<>();

        for (ParameterDefinition parameter : definition.parameters()) {
            Object value = findValue(parameter, intent, knownValues);
            if (value != null) {
                if (valid(parameter, value)) {
                    values.put(parameter.name(), value);
                } else {
                    invalidValues.add(parameter.name() + "=" + value + "（违反 " + rangeText(parameter) + "）");
                    missingRequired.add(parameter.name());
                }
                continue;
            }
            if (parameter.defaultValue() != null) {
                values.put(parameter.name(), parameter.defaultValue());
                continue;
            }
            if (parameter.required()) {
                missingRequired.add(parameter.name());
                clarifyingQuestions.add(clarify(parameter));
            }
        }
        return new Extraction(Map.copyOf(values), List.copyOf(missingRequired),
                List.copyOf(invalidValues), List.copyOf(clarifyingQuestions));
    }

    private Object findValue(ParameterDefinition parameter, ExperimentIntent intent,
                             Map<String, Object> knownValues) {
        if (knownValues != null && knownValues.containsKey(parameter.name())) {
            return knownValues.get(parameter.name());
        }
        if (intent != null) {
            Object supplied = intent.suppliedParameters().get(parameter.name());
            if (supplied != null) return supplied;
            // Natural-language range like "0~10 dB" for the start/end pair: if the user gave
            // one number and the schema asks for start/end, interpret conservatively.
            if ((parameter.name().endsWith("Start") || parameter.name().endsWith("End"))
                    && intent.suppliedParameters().containsKey("range")) {
                Object range = intent.suppliedParameters().get("range");
                if (range instanceof String raw && raw.matches(".*\\d+.*")) {
                    String[] parts = raw.replaceAll("[^0-9.~至到-]", " ").trim().split("\\s+");
                    if (parts.length >= 1) {
                        try {
                            double number = Double.parseDouble(parts[0].replace("~", "").replace("至", "").replace("到", ""));
                            if (parameter.name().endsWith("Start")) return number;
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
        }
        return null;
    }

    private boolean valid(ParameterDefinition parameter, Object value) {
        if (value instanceof Number number) {
            if (parameter.min() != null && number.doubleValue() < parameter.min()) return false;
            if (parameter.max() != null && number.doubleValue() > parameter.max()) return false;
        }
        return true;
    }

    private String clarify(ParameterDefinition parameter) {
        String unit = parameter.unit() == null || parameter.unit().isBlank() ? "" : " [" + parameter.unit() + "]";
        String hint = parameter.description() == null || parameter.description().isBlank()
                ? "" : "（" + parameter.description() + "）";
        return "还需要" + parameter.name() + unit + hint;
    }

    private String rangeText(ParameterDefinition parameter) {
        StringBuilder text = new StringBuilder();
        if (parameter.min() != null) text.append("min=").append(parameter.min());
        if (parameter.max() != null) text.append(text.isEmpty() ? "" : "，").append("max=").append(parameter.max());
        return text.isEmpty() ? "范围" : text.toString();
    }

    public record Extraction(Map<String, Object> values, List<String> missingRequired,
                             List<String> invalidValues, List<String> clarifyingQuestions) {

        public Extraction {
            values = values == null ? Map.of() : Map.copyOf(values);
            missingRequired = missingRequired == null ? List.of() : List.copyOf(missingRequired);
            invalidValues = invalidValues == null ? List.of() : List.copyOf(invalidValues);
            clarifyingQuestions = clarifyingQuestions == null ? List.of() : List.copyOf(clarifyingQuestions);
        }

        public boolean complete() {
            return missingRequired.isEmpty() && invalidValues.isEmpty();
        }
    }
}
