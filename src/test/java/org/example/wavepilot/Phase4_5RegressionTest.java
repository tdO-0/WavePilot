package org.example.wavepilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class Phase4_5RegressionTest {
    @Test void keepsPhase4_5AlgorithmRunnerValidatorAndFixtureCoverage() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.runner.LocalMatlabExperimentRunnerTest"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.validation.RealAlgorithmResultValidatorTest"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.RealPolarAlgorithmManifestTest"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.RealPolarAlgorithmTemplateTest"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.IntegrationFixtureSeparationTest"));
    }
}
