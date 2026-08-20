package org.example.wavepilot.template.definition;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the declarative experiment-definition YAML into typed records. Parsing is manual
 * (no reflection): every field is read with explicit type checks and unknown fields are
 * rejected so a typo can never silently produce a permissive contract.
 */
@Component
public class ExperimentDefinitionParser {

    public ExperimentDefinition parse(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new DefinitionParseException("experiment-definition.yaml must not be blank");
        }
        Object root = new Yaml().load(yamlText);
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new DefinitionParseException("experiment-definition.yaml root must be a mapping");
        }
        Map<String, Object> fields = stringKeys(rootMap);
        rejectUnknown(fields, List.of("templateId", "experimentTypeId", "displayName", "version",
                "entryPoint", "description", "parameters", "outputs", "metrics", "replay",
                "algorithm", "customExtensionRequired", "capabilities"));

        AlgorithmMetadata algorithm = parseAlgorithm(requireMap(fields, "algorithm"));
        return new ExperimentDefinition(
                requireText(fields, "templateId"),
                requireText(fields, "experimentTypeId"),
                requireText(fields, "displayName"),
                requireText(fields, "version"),
                requireText(fields, "entryPoint"),
                text(fields, "description", ""),
                parseParameters(requireList(fields, "parameters")),
                parseOutputs(requireMap(fields, "outputs")),
                parseMetrics(requireList(fields, "metrics")),
                parseReplay(requireList(fields, "replay")),
                algorithm,
                bool(fields, "customExtensionRequired", false),
                parseCapabilities(fields.get("capabilities")));
    }

    private TemplateCapabilities parseCapabilities(Object raw) {
        if (raw == null) return TemplateCapabilities.empty();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new DefinitionParseException("capabilities must be a mapping");
        }
        Map<String, Object> map = stringKeys(rawMap);
        rejectUnknown(map, List.of("experimentFamily", "objective", "modulation", "coding",
                "channel", "tags", "aliases"));
        return new TemplateCapabilities(
                text(map, "experimentFamily", null),
                text(map, "objective", null),
                text(map, "modulation", null),
                text(map, "coding", null),
                text(map, "channel", null),
                stringList(map.get("tags")),
                stringList(map.get("aliases")));
    }

    private List<ParameterDefinition> parseParameters(List<Object> rawList) {
        List<ParameterDefinition> result = new ArrayList<>();
        for (Object item : rawList) {
            Map<String, Object> map = stringKeys(requireMapping(item, "parameter"));
            rejectUnknown(map, List.of("name", "type", "required", "defaultValue", "min", "max",
                    "minExclusive", "maxExclusive", "enumValues", "sweep", "step", "description", "unit"));
            result.add(new ParameterDefinition(
                    requireText(map, "name"),
                    ParameterDefinition.ParameterType.valueOf(requireText(map, "type")),
                    bool(map, "required", false),
                    map.get("defaultValue"),
                    number(map, "min"),
                    number(map, "max"),
                    bool(map, "minExclusive", false),
                    bool(map, "maxExclusive", false),
                    stringList(map.get("enumValues")),
                    bool(map, "sweep", false),
                    number(map, "step"),
                    text(map, "description", ""),
                    text(map, "unit", "")));
        }
        return result;
    }

    private OutputContractDefinition parseOutputs(Map<String, Object> map) {
        rejectUnknown(map, List.of("csvFile", "requiredColumns", "numericColumns",
                "rejectNonFinite", "columnBounds", "jsonRequiredFields", "requiredArtifacts"));
        Map<String, List<Double>> bounds = new LinkedHashMap<>();
        Object boundsRaw = map.get("columnBounds");
        if (boundsRaw instanceof Map<?, ?> boundsMap) {
            for (Map.Entry<?, ?> entry : boundsMap.entrySet()) {
                List<Double> range = new ArrayList<>();
                for (Object value : (List<?>) entry.getValue()) {
                    range.add(asNumber(value, "columnBounds value"));
                }
                bounds.put(String.valueOf(entry.getKey()), range);
            }
        }
        return new OutputContractDefinition(
                text(map, "csvFile", "accuracy.csv"),
                stringList(map.get("requiredColumns")),
                stringList(map.get("numericColumns")),
                bool(map, "rejectNonFinite", true),
                bounds,
                stringList(map.get("jsonRequiredFields")),
                stringList(map.get("requiredArtifacts")));
    }

    private List<MetricDefinition> parseMetrics(List<Object> rawList) {
        List<MetricDefinition> result = new ArrayList<>();
        for (Object item : rawList) {
            Map<String, Object> map = stringKeys(requireMapping(item, "metric"));
            rejectUnknown(map, List.of("metricName", "displayName", "unit", "sourceColumn",
                    "aggregation", "groupByDimensions"));
            result.add(new MetricDefinition(
                    requireText(map, "metricName"),
                    text(map, "displayName", requireText(map, "metricName")),
                    text(map, "unit", ""),
                    requireText(map, "sourceColumn"),
                    MetricDefinition.Aggregation.valueOf(requireText(map, "aggregation")),
                    stringList(map.get("groupByDimensions"))));
        }
        return result;
    }

    private List<ReplayMetricDefinition> parseReplay(List<Object> rawList) {
        List<ReplayMetricDefinition> result = new ArrayList<>();
        for (Object item : rawList) {
            Map<String, Object> map = stringKeys(requireMapping(item, "replay metric"));
            rejectUnknown(map, List.of("comparisonColumn", "maxAbsoluteTolerance",
                    "meanAbsoluteTolerance", "compareMean", "required"));
            result.add(new ReplayMetricDefinition(
                    requireText(map, "comparisonColumn"),
                    number(map, "maxAbsoluteTolerance"),
                    number(map, "meanAbsoluteTolerance"),
                    bool(map, "compareMean", false),
                    bool(map, "required", true)));
        }
        return result;
    }

    private AlgorithmMetadata parseAlgorithm(Map<String, Object> map) {
        rejectUnknown(map, List.of("name", "version", "classification",
                "algorithmValidated", "validationReference"));
        boolean validated = bool(map, "algorithmValidated", false);
        String reference = text(map, "validationReference", "");
        if (validated && reference.isBlank()) {
            throw new DefinitionParseException(
                    "algorithmValidated=true requires an explicit validationReference");
        }
        return new AlgorithmMetadata(
                requireText(map, "name"),
                requireText(map, "version"),
                requireText(map, "classification"),
                validated,
                reference.isBlank() ? null : reference);
    }

    private Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new DefinitionParseException("definition keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private void rejectUnknown(Map<String, Object> map, List<String> allowed) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new DefinitionParseException("Unknown definition field: " + key);
            }
        }
    }

    private String requireText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new DefinitionParseException("Definition field is required: " + key);
        }
        return text;
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) {
            throw new DefinitionParseException("Definition field must be boolean: " + key);
        }
        return bool;
    }

    private Double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        return asNumber(value, key);
    }

    private Double asNumber(Object value, String key) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new DefinitionParseException("Definition field must be numeric: " + key);
    }

    private List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new DefinitionParseException("Definition field must be a list of strings");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(String.valueOf(item));
        return result;
    }

    private Map<String, Object> requireMap(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new DefinitionParseException("Definition field must be a mapping: " + key);
        }
        return stringKeys(map);
    }

    private List<Object> requireList(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof List<?> list)) {
            throw new DefinitionParseException("Definition field must be a list: " + key);
        }
        return new ArrayList<>(list);
    }

    private Map<String, Object> requireMapping(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new DefinitionParseException("Each " + what + " must be a mapping");
        }
        return stringKeys(map);
    }

    public static class DefinitionParseException extends RuntimeException {
        public DefinitionParseException(String message) { super(message); }
    }
}
