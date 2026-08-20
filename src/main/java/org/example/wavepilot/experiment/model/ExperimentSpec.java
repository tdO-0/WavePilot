package org.example.wavepilot.experiment.model;

import java.util.List;
import java.util.Map;

/**
 * LLM may propose this structure, but only Java validation can authorize execution.
 * Deliberately contains no shell or MATLAB command field.
 *
 * The legacy {@code experimentType} enum covers the built-in types. Declarative template
 * types (registered through ExperimentDefinition) travel in {@code experimentTypeId} with
 * their custom parameters in {@code customParameters}; both are null for legacy requests,
 * so old clients are fully compatible.
 */
public record ExperimentSpec(
        ExperimentType experimentType,
        List<Integer> codeLengths,
        double errorRateStart,
        double errorRateEnd,
        double errorRateStep,
        int sampleCount,
        int monteCarloTimes,
        long randomSeed,
        List<OutputType> outputTypes,
        String description,
        String experimentTypeId,
        Map<String, Object> customParameters) {

    /** Legacy constructor; keeps every existing call site source-compatible. */
    public ExperimentSpec(ExperimentType experimentType, List<Integer> codeLengths,
                          double errorRateStart, double errorRateEnd, double errorRateStep,
                          int sampleCount, int monteCarloTimes, long randomSeed,
                          List<OutputType> outputTypes, String description) {
        this(experimentType, codeLengths, errorRateStart, errorRateEnd, errorRateStep,
                sampleCount, monteCarloTimes, randomSeed, outputTypes, description,
                null, Map.of());
    }

    public ExperimentSpec {
        experimentTypeId = experimentTypeId == null || experimentTypeId.isBlank() ? null : experimentTypeId;
        // Null values are dropped instead of rejected: Map.copyOf would throw NPE on them,
        // and a missing parameter is reported as "Missing required parameter" by the
        // declarative validator, which is the correct signal for the user to fill it in.
        Map<String, Object> clean = new java.util.LinkedHashMap<>();
        if (customParameters != null) {
            customParameters.forEach((key, value) -> {
                if (value != null) clean.put(key, value);
            });
        }
        customParameters = Map.copyOf(clean);
    }
}
