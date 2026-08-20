package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workbench must always display the mock/real, classification and validation boundaries. */
class FrontendMockRealBoundaryTest {

    @Test
    void bothExperimentBoundaryLabelsAreRendered() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("MOCK EXPERIMENT"), "mock boundary label missing");
        assertTrue(app.contains("REAL MATLAB EXPERIMENT"), "real boundary label missing");
    }

    @Test
    void simplifiedBaselineAndUnvalidatedAlgorithmFlagsAreAlwaysShown() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("SIMPLIFIED_BASELINE"), "simplified baseline flag missing");
        assertTrue(app.contains("algorithmValidated=false"), "algorithmValidated=false flag missing");
        assertTrue(app.contains("algorithmValidated"), "boundary rendering must read the server flag");
    }

    @Test
    void boundaryIsDerivedFromServerArtifactMetadataNotFromLocalGuesswork() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("record.mock"), "mock flag must come from the artifact record");
        assertTrue(app.contains("record.runnerType"), "runner type must come from the artifact record");
        assertTrue(app.contains("record.classification"), "classification must come from the artifact record");
    }

    @Test
    void unknownBoundaryIsShownInsteadOfFabricatingOne() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("边界：未确定"), "unknown boundary state must be explicit");
        assertTrue(app.contains("任务边界：读取产物中"), "pending boundary state must be explicit");
    }

    @Test
    void replayConsistencyIsNeverPresentedAsAlgorithmValidation() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("Replay 一致不代表算法已验证"), "replay boundary disclaimer missing");
        assertTrue(app.contains("algorithmValidated=false 始终保留"), "replay boundary retention missing");
    }
}
