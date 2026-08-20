package org.example.wavepilot.agent.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentSpecClarificationTest {

    @Test
    void asksForMissingMonteCarloTimes() {
        ExperimentSpecParseResult result = parse(candidate("\"sampleCount\":500,\"monteCarloTimes\":null"));
        assertClarification(result, "monteCarloTimes", "蒙特卡洛");
    }

    @Test
    void asksForMissingSampleCount() {
        ExperimentSpecParseResult result = parse(candidate("\"sampleCount\":null,\"monteCarloTimes\":1000"));
        assertClarification(result, "sampleCount", "样本");
    }

    @Test
    void genericPolarRequestDoesNotInventParameters() {
        ExperimentSpecParseResult result = parse("""
                {"relevant":true,"experimentType":"POLAR_CODE_K_IDENTIFICATION",
                "codeLengths":null,"errorRateStart":null,"errorRateEnd":null,"errorRateStep":null,
                "sampleCount":null,"monteCarloTimes":null,"randomSeed":null,"outputTypes":null}
                """);

        assertEquals(ExperimentSpecParseStatus.NEEDS_CLARIFICATION, result.parseStatus());
        assertNull(result.experimentSpec());
        assertTrue(result.missingFields().size() >= 6);
    }

    private void assertClarification(ExperimentSpecParseResult result, String field, String questionText) {
        assertEquals(ExperimentSpecParseStatus.NEEDS_CLARIFICATION, result.parseStatus());
        assertNull(result.experimentSpec());
        assertTrue(result.missingFields().contains(field));
        assertTrue(result.clarificationQuestions().stream().anyMatch(question -> question.contains(questionText)));
    }

    private ExperimentSpecParseResult parse(String output) {
        ExperimentSpecParser parser = new ExperimentSpecParser(prompt -> output,
                new ObjectMapper().findAndRegisterModules(), new ExperimentSpecValidator(), 20L);
        return parser.parse("帮我做极化码实验");
    }

    private String candidate(String counts) {
        return "{\"relevant\":true,\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\","
                + "\"codeLengths\":[32,64],\"errorRateStart\":0.0,\"errorRateEnd\":0.2,"
                + "\"errorRateStep\":0.01," + counts + ",\"randomSeed\":null,\"outputTypes\":null}";
    }
}
