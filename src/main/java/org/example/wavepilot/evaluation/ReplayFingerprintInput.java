package org.example.wavepilot.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Canonical replay inputs; the spec is either the legacy polar {@code ExperimentSpec} or a
 * {@code GenericExperimentSpec} for declarative templates — both are canonicalized to JSON
 * for the fingerprint.
 */
public record ReplayFingerprintInput(
        Object experimentSpec,
        String experimentTemplateVersion,
        long randomSeed,
        String matlabScriptSha256,
        Map<String, String> criticalConfig,
        List<String> artifactSha256) {

    public ReplayFingerprintInput {
        criticalConfig = criticalConfig == null ? Map.of() : Map.copyOf(criticalConfig);
        artifactSha256 = artifactSha256 == null ? List.of() : List.copyOf(artifactSha256);
    }
}
