package org.example.wavepilot.template.definition;

import java.util.List;

/**
 * A fully declarative experiment definition: parameters, output contract, report metrics,
 * replay strategy and algorithm metadata. Templates carrying rules the declarative system
 * cannot express must set {@code customExtensionRequired=true} so they are never silently
 * treated as automatically supported.
 */
public record ExperimentDefinition(
        String templateId,
        String experimentTypeId,
        String displayName,
        String version,
        String entryPoint,
        String description,
        List<ParameterDefinition> parameters,
        OutputContractDefinition outputs,
        List<MetricDefinition> metrics,
        List<ReplayMetricDefinition> replay,
        AlgorithmMetadata algorithm,
        boolean customExtensionRequired,
        TemplateCapabilities capabilities) {

    public ExperimentDefinition {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        replay = replay == null ? List.of() : List.copyOf(replay);
        capabilities = capabilities == null ? TemplateCapabilities.empty() : capabilities;
    }

    /** Backward-compatible constructor used by older call sites and tests. */
    public ExperimentDefinition(String templateId, String experimentTypeId, String displayName,
                                String version, String entryPoint, String description,
                                List<ParameterDefinition> parameters, OutputContractDefinition outputs,
                                List<MetricDefinition> metrics, List<ReplayMetricDefinition> replay,
                                AlgorithmMetadata algorithm, boolean customExtensionRequired) {
        this(templateId, experimentTypeId, displayName, version, entryPoint, description,
                parameters, outputs, metrics, replay, algorithm, customExtensionRequired,
                TemplateCapabilities.empty());
    }
}
