package org.example.wavepilot.agent.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentSpecParserTest {

    @Test
    void parsesCompleteParametersAndMarksDefaults() {
        ExperimentSpecParseResult result = parser("""
                {"relevant":true,"experimentType":"POLAR_CODE_K_IDENTIFICATION",
                "codeLengths":[32,64,128,256],"errorRateStart":0.0,"errorRateEnd":0.2,
                "errorRateStep":0.01,"sampleCount":500,"monteCarloTimes":1000,
                "randomSeed":null,"outputTypes":null,"description":"compare polar codes"}
                """).parse("run the polar experiment");

        assertEquals(ExperimentSpecParseStatus.COMPLETE, result.parseStatus());
        assertTrue(result.validationResult().valid());
        assertEquals(20L, result.experimentSpec().randomSeed());
        assertTrue(result.defaultedFields().contains("randomSeed"));
        assertTrue(result.defaultedFields().contains("outputTypes"));
    }

    @Test
    void javaValidatorRejectsNonPowerOfTwoLength() {
        ExperimentSpecParseResult result = parser(validJson().replace("[32,64]", "[32,48]"))
                .parse("use length 32 and 48");

        assertEquals(ExperimentSpecParseStatus.INVALID, result.parseStatus());
        assertFalse(result.validationResult().valid());
        assertTrue(result.validationResult().errors().stream().anyMatch(error -> error.contains("power of two")));
    }

    @Test
    void javaValidatorRejectsReversedErrorRateRange() {
        ExperimentSpecParseResult result = parser(validJson()
                .replace("\"errorRateStart\":0.0", "\"errorRateStart\":0.2")
                .replace("\"errorRateEnd\":0.2", "\"errorRateEnd\":0.1"))
                .parse("invalid range");

        assertEquals(ExperimentSpecParseStatus.INVALID, result.parseStatus());
        assertTrue(result.validationResult().errors().stream().anyMatch(error -> error.contains("less than")));
    }

    @Test
    void rejectsUnrelatedMessage() {
        ExperimentSpecParseResult result = parser("{\"relevant\":false}").parse("write a restaurant review");
        assertEquals(ExperimentSpecParseStatus.INVALID, result.parseStatus());
        assertTrue(result.validationResult().errors().get(0).contains("UNRELATED_REQUEST"));
    }

    @Test
    void convertsInvalidModelJsonToRecognizableResult() {
        ExperimentSpecParseResult result = parser("not-json").parse("run experiment");
        assertEquals(ExperimentSpecParseStatus.INVALID, result.parseStatus());
        assertTrue(result.validationResult().errors().get(0).contains("MODEL_OUTPUT_INVALID_JSON"));
    }

    private ExperimentSpecParser parser(String output) {
        return new ExperimentSpecParser(prompt -> output, new ObjectMapper().findAndRegisterModules(),
                new ExperimentSpecValidator(), 20L);
    }

    private String validJson() {
        return """
                {"relevant":true,"experimentType":"POLAR_CODE_K_IDENTIFICATION",
                "codeLengths":[32,64],"errorRateStart":0.0,"errorRateEnd":0.2,
                "errorRateStep":0.01,"sampleCount":500,"monteCarloTimes":1000,
                "randomSeed":20,"outputTypes":["ACCURACY_CSV","RUN_LOG"],"description":"test"}
                """;
    }
}
