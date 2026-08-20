package org.example.wavepilot;

import org.example.wavepilot.artifact.ArtifactType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards that the Phase 5A/5B report, citation and artifact work survives the Replay phase.
 */
class Phase5BRegressionTest {

    @Test
    void keepsPhase5A5BReportCitationAndArtifactCoverage() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.report.ArtifactCitation"));
        assertNotNull(Class.forName("org.example.wavepilot.report.CitationStatus"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportDataAssembler"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportCitationValidator"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportService"));
        assertNotNull(Class.forName("org.example.wavepilot.report.TemplateExperimentReportGenerator"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ControlledReportAgent"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportController"));
        assertNotNull(Class.forName("org.example.wavepilot.artifact.ArtifactController"));
        assertNotNull(Class.forName("org.example.wavepilot.artifact.ArtifactRecord"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportCitationValidatorTest"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportControllerContractTest"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportAgentBoundaryTest"));
        assertNotNull(Class.forName("org.example.wavepilot.report.ArtifactHashMismatchTest"));
        assertNotNull(Class.forName("org.example.wavepilot.report.CrossJobCitationRejectedTest"));
    }

    @Test
    void replayArtifactTypesExtendWithoutBreakingTheReportTypes() {
        assertNotNull(ArtifactType.valueOf("REPLAY_MANIFEST"));
        assertNotNull(ArtifactType.valueOf("REPLAY_COMPARISON"));
        assertNotNull(ArtifactType.valueOf("FINAL_REPORT"));
        assertNotNull(ArtifactType.valueOf("ACCURACY_CSV"));
    }
}
