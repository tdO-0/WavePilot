package org.example.wavepilot.experiment.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic experiment specification for declarative templates. There is no fake polar
 * experimentType, no fake codeLengths/errorRate fields: the spec names the template
 * (experimentTypeId/templateId/templateVersion) and carries the runtime parameters as a
 * map. Artifacts requested through {@code requestedArtifacts}. The legacy
 * {@link ExperimentSpec} stays for the built-in polar experiment type; declarative jobs
 * must use this spec so QPSK/OFDM/BEC jobs never look like polar ones.
 */
public record GenericExperimentSpec(
        String experimentTypeId,
        String templateId,
        String templateVersion,
        Map<String, Object> parameters,
        Long randomSeed,
        List<String> requestedArtifacts,
        String description) {

    public GenericExperimentSpec {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        requestedArtifacts = requestedArtifacts == null ? List.of() : List.copyOf(requestedArtifacts);
        templateVersion = templateVersion == null || templateVersion.isBlank() ? null : templateVersion;
    }

    public static GenericExperimentSpec of(String experimentTypeId, String templateId,
                                           Map<String, Object> parameters) {
        return new GenericExperimentSpec(experimentTypeId, templateId, null,
                parameters == null ? Map.of() : new LinkedHashMap<>(parameters),
                null, List.of("ACCURACY_CSV", "RUN_LOG"), "");
    }

    public Object parameter(String name) {
        return parameters.get(name);
    }
}
