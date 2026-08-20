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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The legacy enum must keep working untouched while declarative type ids are supported. */
class DynamicExperimentTypeIdCompatibilityTest {

    @Test
    void legacyPolarSpecStillValidatesWithoutAnyRegistry() {
        ExperimentSpecValidator validator = new ExperimentSpecValidator();
        ValidationResult result = validator.validate(org.example.wavepilot.WavePilotTestFixtures.validSpec());
        assertTrue(result.valid(), "legacy polar spec must stay valid: " + result.errors());
        assertEquals(3, validator.calculateErrorRatePointCount(
                org.example.wavepilot.WavePilotTestFixtures.validSpec()));
    }

    @Test
    void legacyPolarSpecAlsoWorksWithARegistryPresent() {
        ExperimentDefinitionRegistry registry = DeclarativeTestSupport.registryWithDemo();
        ExperimentSpecValidator validator = new ExperimentSpecValidator(registry);
        ValidationResult result = validator.validate(org.example.wavepilot.WavePilotTestFixtures.validSpec());
        assertTrue(result.valid(), "registry must not change legacy behavior: " + result.errors());
    }

    @Test
    void declarativeTypeIdTravelsThroughTheSpecJson() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV), "json round trip",
                DeclarativeTestSupport.DEMO_TYPE_ID, Map.of("frames", 100));
        String json = mapper.writeValueAsString(spec);
        ExperimentSpec back = mapper.readValue(json, ExperimentSpec.class);
        assertEquals(DeclarativeTestSupport.DEMO_TYPE_ID, back.experimentTypeId());
        assertEquals(100, back.customParameters().get("frames"));
        // A legacy JSON without the new fields still deserializes.
        ExperimentSpec legacy = mapper.readValue(
                "{\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\",\"codeLengths\":[32],"
                        + "\"errorRateStart\":0.0,\"errorRateEnd\":0.1,\"errorRateStep\":0.05,"
                        + "\"sampleCount\":20,\"monteCarloTimes\":10,\"randomSeed\":20,"
                        + "\"outputTypes\":[\"ACCURACY_CSV\"]}",
                ExperimentSpec.class);
        assertEquals(null, legacy.experimentTypeId());
        assertTrue(legacy.customParameters().isEmpty());
    }

    @Test
    void nullCustomParameterValuesNeverBreakDeserialization() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // A client (or the workbench fill) may send null for not-yet-filled parameters;
        // the spec must deserialize and the nulls must be dropped, not throw NPE.
        ExperimentSpec spec = mapper.readValue(
                "{\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\",\"experimentTypeId\":\"demo-ber-awgn\","
                        + "\"codeLengths\":[32],\"errorRateStart\":0.0,\"errorRateEnd\":0.1,"
                        + "\"errorRateStep\":0.05,\"sampleCount\":20,\"monteCarloTimes\":10,"
                        + "\"randomSeed\":20,\"outputTypes\":[\"ACCURACY_CSV\"],"
                        + "\"customParameters\":{\"ebNoStart\":0,\"ebNoEnd\":null,\"ebNoStep\":0.5,\"frames\":null}}",
                ExperimentSpec.class);
        assertEquals(2, spec.customParameters().size());
        assertEquals(0, spec.customParameters().get("ebNoStart"));
        assertTrue(!spec.customParameters().containsKey("frames"),
                "null parameters must be dropped so the validator reports them as missing");
    }
}
