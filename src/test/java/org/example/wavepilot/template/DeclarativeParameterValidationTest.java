package org.example.wavepilot.template;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarativeParameterValidationTest {

    private final ExperimentDefinitionRegistry registry = DeclarativeTestSupport.registryWithDemo();
    private final ExperimentSpecValidator validator = new ExperimentSpecValidator(registry);

    @Test
    void aValidDeclarativeSpecPasses() {
        ValidationResult result = validator.validate(spec(Map.of(
                "ebNoStart", 0.0, "ebNoEnd", 12.0, "ebNoStep", 0.5, "frames", 1000,
                "modulation", "QPSK")));
        assertTrue(result.valid(), "valid spec must pass: " + result.errors());
    }

    @Test
    void missingRequiredParametersAreRejected() {
        ValidationResult result = validator.validate(spec(Map.of("ebNoStart", 0.0)));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("ebNoEnd")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("frames")));
    }

    @Test
    void outOfBoundsAndBadTypesAreRejected() {
        ValidationResult aboveMax = validator.validate(spec(Map.of(
                "ebNoStart", 0.0, "ebNoEnd", 12.0, "ebNoStep", 0.5, "frames", 5)));
        assertFalse(aboveMax.valid());
        assertTrue(aboveMax.errors().stream().anyMatch(error -> error.contains("frames")),
                "frames below min must be rejected: " + aboveMax.errors());

        ValidationResult badEnum = validator.validate(spec(Map.of(
                "ebNoStart", 0.0, "ebNoEnd", 12.0, "ebNoStep", 0.5, "frames", 1000,
                "modulation", "64QAM")));
        assertFalse(badEnum.valid());
        assertTrue(badEnum.errors().stream().anyMatch(error -> error.contains("modulation")));
    }

    @Test
    void unknownParametersAreRejected() {
        ValidationResult result = validator.validate(spec(Map.of(
                "ebNoStart", 0.0, "ebNoEnd", 12.0, "ebNoStep", 0.5, "frames", 1000,
                "evilParameter", 1)));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Unknown parameter")));
    }

    @Test
    void pointCountComesFromTheDeclaredSweepGrid() {
        ExperimentSpec spec = spec(Map.of(
                "ebNoStart", 0.0, "ebNoEnd", 12.0, "ebNoStep", 0.5, "frames", 1000));
        assertEquals(21, validator.calculateErrorRatePointCount(spec),
                "sweepEbNo 0..20 step 1 must yield 21 grid points");
    }

    @Test
    void unregisteredExperimentTypeIdIsRejectedExplicitly() {
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV), "x", "not-a-registered-type", Map.of());
        ValidationResult result = validator.validate(spec);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Unsupported experiment type")));
    }

    private ExperimentSpec spec(Map<String, Object> parameters) {
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "declarative test",
                DeclarativeTestSupport.DEMO_TYPE_ID, parameters);
    }
}
