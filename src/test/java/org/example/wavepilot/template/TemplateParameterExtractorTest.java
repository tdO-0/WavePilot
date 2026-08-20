package org.example.wavepilot.template;

import org.example.wavepilot.intent.ExperimentIntent;
import org.example.wavepilot.intent.IntentType;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ParameterDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameter extraction is schema-authoritative: only declared parameters are used, safe
 * defaults fill optional ones, and only genuinely missing required parameters become
 * clarifying questions — never a re-ask of everything.
 */
class TemplateParameterExtractorTest {

    private ExperimentDefinition qpskDefinition() {
        return new ExperimentDefinition("qpsk-awgn-ber", "qpsk-awgn-ber", "QPSK BER", "1.0.0",
                "run_experiment", "", List.of(
                        new ParameterDefinition("ebNoStart", ParameterDefinition.ParameterType.NUMBER,
                                true, 0.0, 0.0, 20.0, false, false, List.of(), true, 1.0, "Eb/N0 起始", "dB"),
                        new ParameterDefinition("ebNoEnd", ParameterDefinition.ParameterType.NUMBER,
                                true, 10.0, 0.0, 20.0, false, false, List.of(), true, 1.0, "Eb/N0 结束", "dB"),
                        new ParameterDefinition("ebNoStep", ParameterDefinition.ParameterType.NUMBER,
                                false, 1.0, 0.1, 2.0, false, false, List.of(), true, 1.0, "步长", "dB"),
                        new ParameterDefinition("symbolCount", ParameterDefinition.ParameterType.INTEGER,
                                true, null, 100.0, 1_000_000_000.0, false, false, List.of(),
                                false, null, "符号数量", "symbols"),
                        new ParameterDefinition("randomSeed", ParameterDefinition.ParameterType.INTEGER,
                                false, 20L, 1.0, null, false, false, List.of(), false, null, "随机种子", "")),
                null, null, null, null, false);
    }

    @Test
    void givenValuesFillTheSchemaAndDefaultsCoverTheRest() {
        TemplateParameterExtractor extractor = new TemplateParameterExtractor();
        ExperimentIntent intent = new ExperimentIntent(IntentType.RUN_EXPERIMENT, "QPSK BER",
                "MODULATION", "QPSK", null, "AWGN",
                Map.of("ebNoStart", 0, "ebNoEnd", 10), List.of("BER"), List.of(),
                List.of(), List.of(), List.of(), 0.9);

        TemplateParameterExtractor.Extraction extraction =
                extractor.extract(qpskDefinition(), intent, Map.of());

        assertEquals(0, extraction.values().get("ebNoStart"));
        assertEquals(10, extraction.values().get("ebNoEnd"));
        assertEquals(1.0, extraction.values().get("ebNoStep"), "safe default step");
        assertEquals(20L, extraction.values().get("randomSeed"), "safe default seed");
        assertEquals(List.of("symbolCount"), extraction.missingRequired(),
                "only the genuinely missing required parameter is asked");
        assertTrue(extraction.clarifyingQuestions().get(0).contains("symbolCount"),
                "the question must target only symbolCount");
    }

    @Test
    void invalidValuesAreRejectedNotSilentlyAccepted() {
        TemplateParameterExtractor extractor = new TemplateParameterExtractor();
        ExperimentIntent intent = new ExperimentIntent(IntentType.RUN_EXPERIMENT, "QPSK BER",
                "MODULATION", "QPSK", null, "AWGN",
                Map.of("ebNoStart", -5, "ebNoEnd", 10), List.of("BER"), List.of(),
                List.of(), List.of(), List.of(), 0.9);

        TemplateParameterExtractor.Extraction extraction =
                extractor.extract(qpskDefinition(), intent, Map.of());

        assertFalse(extraction.complete());
        assertTrue(extraction.invalidValues().get(0).contains("ebNoStart"),
                "out-of-range values must be reported as invalid");
    }

    @Test
    void parametersNotInTheSchemaAreNeverInvented() {
        TemplateParameterExtractor extractor = new TemplateParameterExtractor();
        ExperimentIntent intent = new ExperimentIntent(IntentType.RUN_EXPERIMENT, "QPSK BER",
                "MODULATION", "QPSK", null, "AWGN",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "modulationOrder", 8), List.of("BER"),
                List.of(), List.of(), List.of(), List.of(), 0.9);

        TemplateParameterExtractor.Extraction extraction =
                extractor.extract(qpskDefinition(), intent, Map.of());

        assertFalse(extraction.values().containsKey("modulationOrder"),
                "schema-unknown parameters must be dropped, not invented as NUMBER");
        assertEquals(List.of("symbolCount"), extraction.missingRequired());
    }

    @Test
    void naturalLanguageAnswerFromTheIntentCompletesTheSchema() {
        TemplateParameterExtractor extractor = new TemplateParameterExtractor();
        ExperimentIntent intent = new ExperimentIntent(IntentType.RUN_EXPERIMENT, "QPSK BER",
                "MODULATION", "QPSK", null, "AWGN",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "symbolCount", 100000),
                List.of("BER"), List.of(), List.of(), List.of(), List.of(), 0.9);
        // The LLM intent resolver turns "10万个符号" into the numeric 100000; the extractor
        // then accepts it as the schema value and asks nothing more.
        TemplateParameterExtractor.Extraction extraction =
                extractor.extract(qpskDefinition(), intent, Map.of());
        assertTrue(extraction.complete(), "a resolved natural-language answer must complete the schema");
        assertEquals(100000, extraction.values().get("symbolCount"));
    }

    @Test
    void completeExtractionNeedsNoClarification() {
        TemplateParameterExtractor extractor = new TemplateParameterExtractor();
        ExperimentIntent intent = new ExperimentIntent(IntentType.RUN_EXPERIMENT, "QPSK BER",
                "MODULATION", "QPSK", null, "AWGN",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "symbolCount", 100_000), List.of("BER"),
                List.of(), List.of(), List.of(), List.of(), 0.9);

        TemplateParameterExtractor.Extraction extraction =
                extractor.extract(qpskDefinition(), intent, Map.of());

        assertTrue(extraction.complete());
        assertEquals(100_000, extraction.values().get("symbolCount"));
    }
}
