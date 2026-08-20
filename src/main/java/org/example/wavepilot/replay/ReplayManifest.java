package org.example.wavepilot.replay;

import java.time.Instant;

/**
 * Immutable record of everything that defines one replay: the source job, the canonical
 * ExperimentSpec, the exact runner/template/algorithm axes and the computed fingerprint.
 * Registered as a REPLAY_MANIFEST artifact in the replay job's directory.
 */
public record ReplayManifest(
        String replayId,
        String sourceJobId,
        String replayJobId,
        String experimentType,
        String canonicalExperimentSpec,
        long randomSeed,
        String runnerType,
        String templateVersion,
        String algorithmName,
        String algorithmVersion,
        String classification,
        boolean mock,
        boolean algorithmValidated,
        String matlabTemplateSha256,
        String javaApplicationVersion,
        String replayFingerprint,
        Instant createdAt) {

    public ReplayManifest withReplayJobId(String replayJobId) {
        return new ReplayManifest(replayId, sourceJobId, replayJobId, experimentType,
                canonicalExperimentSpec, randomSeed, runnerType, templateVersion, algorithmName,
                algorithmVersion, classification, mock, algorithmValidated, matlabTemplateSha256,
                javaApplicationVersion, replayFingerprint, createdAt);
    }
}
