package org.example.wavepilot.intent;

import java.util.List;
import java.util.Map;

/**
 * Structured interpretation of one user request. Optional semantic fields (modulation,
 * coding, channel, experimentFamily) are allowed to be null — the resolver must never
 * invent values the user did not provide. {@code missingCriticalInformation} drives the
 * WAITING_CLARIFICATION state: when non-empty the Agent asks before designing anything.
 */
public record ExperimentIntent(
        IntentType intentType,
        String objective,
        String experimentFamily,
        String modulation,
        String coding,
        String channel,
        Map<String, Object> suppliedParameters,
        List<String> requestedMetrics,
        List<String> requestedOutputs,
        List<String> semanticTags,
        List<String> assumptions,
        List<String> missingCriticalInformation,
        double confidence) {

    public ExperimentIntent {
        suppliedParameters = suppliedParameters == null ? Map.of() : Map.copyOf(suppliedParameters);
        requestedMetrics = requestedMetrics == null ? List.of() : List.copyOf(requestedMetrics);
        requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
        semanticTags = semanticTags == null ? List.of() : List.copyOf(semanticTags);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        missingCriticalInformation =
                missingCriticalInformation == null ? List.of() : List.copyOf(missingCriticalInformation);
    }

    public boolean needsClarification() {
        return !missingCriticalInformation.isEmpty();
    }

    public boolean isExperimentAction() {
        return intentType == IntentType.RUN_EXPERIMENT
                || intentType == IntentType.CREATE_TEMPLATE
                || intentType == IntentType.ANALYZE_RESULT
                || intentType == IntentType.REPLAY_EXPERIMENT
                || intentType == IntentType.RUN_EVAL
                || intentType == IntentType.CANCEL_EXPERIMENT;
    }
}
