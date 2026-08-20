package org.example.wavepilot.experiment.validation;

import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentSpecValidatorTest {

    private final ExperimentSpecValidator validator = new ExperimentSpecValidator();

    @Test
    void acceptsSupportedSafeSpec() {
        assertTrue(validator.validate(WavePilotTestFixtures.validSpec()).valid());
    }

    @Test
    void rejectsEmptyAndNonPowerOfTwoCodeLengths() {
        ExperimentSpec base = WavePilotTestFixtures.validSpec();
        ExperimentSpec invalid = new ExperimentSpec(base.experimentType(), List.of(16, 48),
                base.errorRateStart(), base.errorRateEnd(), base.errorRateStep(), base.sampleCount(),
                base.monteCarloTimes(), base.randomSeed(), base.outputTypes(), base.description());

        ValidationResult result = validator.validate(invalid);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("at least 32")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("power of two")));
    }

    @Test
    void rejectsInvalidRateRangeAndNonPositiveWork() {
        ExperimentSpec base = WavePilotTestFixtures.validSpec();
        ExperimentSpec invalid = new ExperimentSpec(base.experimentType(), base.codeLengths(),
                -0.1, 0.6, 0, 0, -1, base.randomSeed(), base.outputTypes(), base.description());

        ValidationResult result = validator.validate(invalid);

        assertFalse(result.valid());
        assertTrue(result.errors().size() >= 5);
    }

    @Test
    void warnsForLargeButLegalExperiment() {
        ExperimentSpec base = WavePilotTestFixtures.validSpec();
        ExperimentSpec large = new ExperimentSpec(base.experimentType(), List.of(32, 64, 128, 256, 512),
                0, 0.2, 0.01, 500, 1000, base.randomSeed(), base.outputTypes(), base.description());

        ValidationResult result = validator.validate(large);

        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("RESOURCE_RISK")));
    }

    @Test
    void rejectsExtremelySmallStepWithoutThrowing() {
        ExperimentSpec base = WavePilotTestFixtures.validSpec();
        ExperimentSpec invalid = new ExperimentSpec(base.experimentType(), base.codeLengths(),
                0, 0.2, Double.MIN_VALUE, base.sampleCount(), base.monteCarloTimes(),
                base.randomSeed(), base.outputTypes(), base.description());

        ValidationResult result = validator.validate(invalid);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("too many points")));
    }

    @Test
    void rejectsSeedOutsideMatlabRngRange() {
        ExperimentSpec base = WavePilotTestFixtures.validSpec();
        ExperimentSpec invalid = new ExperimentSpec(base.experimentType(), base.codeLengths(),
                base.errorRateStart(), base.errorRateEnd(), base.errorRateStep(), base.sampleCount(),
                base.monteCarloTimes(), 4_294_967_296L, base.outputTypes(), base.description());

        ValidationResult result = validator.validate(invalid);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("MATLAB rng")));
    }
}
