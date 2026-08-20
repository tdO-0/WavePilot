package org.example.wavepilot.template;

import org.example.wavepilot.intent.ExperimentIntent;
import org.example.wavepilot.intent.IntentType;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ParameterDefinition;
import org.example.wavepilot.template.definition.TemplateCapabilities;
import org.example.wavepilot.template.generation.DashScopeTemplateGenerationModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Template resolution and generation must follow experiment semantics: a QPSK BER intent
 * matches the QPSK template, an OFDM intent matches nothing (agent must create a candidate),
 * and the LLM-generated template id is normalized by the backend, never hardcoded.
 */
class TemplateResolverContractTest {

    private ExperimentDefinition template(String id, String family, String modulation,
                                          String coding, String channel, List<String> tags) {
        return new ExperimentDefinition(id, id, id, "1.0.0", "run_experiment", "",
                List.of(new ParameterDefinition("ebNoStart", ParameterDefinition.ParameterType.NUMBER,
                        true, 0.0, 0.0, 20.0, false, false, List.of(), true, 1.0, "", "dB")),
                null, null, null, null, false,
                new TemplateCapabilities(family, null, modulation, coding, channel, tags, List.of()));
    }

    private ExperimentIntent intent(String objective, String modulation, String coding,
                                    String channel, List<String> tags) {
        return new ExperimentIntent(IntentType.RUN_EXPERIMENT, objective, "MODULATION",
                modulation, coding, channel, Map.of(), List.of("BER"), List.of(),
                tags, List.of(), List.of(), 0.9);
    }

    @Test
    void qpskIntentMatchesTheQpskTemplate() {
        TemplateResolver resolver = new TemplateResolver();
        List<ExperimentDefinition> templates = List.of(
                template("qpsk-awgn-ber", "MODULATION", "QPSK", null, "AWGN",
                        List.of("qpsk", "ber", "awgn")),
                template("polar-k-simple", "CODING", null, "POLAR", "BSC",
                        List.of("polar", "k")));

        TemplateResolver.Resolution resolution =
                resolver.resolve(intent("QPSK AWGN BER", "QPSK", null, "AWGN",
                        List.of("qpsk", "ber")), templates);

        assertEquals(TemplateResolver.Verdict.MATCHED, resolution.verdict());
        assertEquals("qpsk-awgn-ber", resolution.matchedTemplateId());
        assertTrue(resolution.score() > 0);
    }

    @Test
    void ofdmIntentDoesNotMatchExistingQpskOrPolarTemplates() {
        TemplateResolver resolver = new TemplateResolver();
        List<ExperimentDefinition> templates = List.of(
                template("qpsk-awgn-ber", "MODULATION", "QPSK", null, "AWGN",
                        List.of("qpsk", "ber")),
                template("polar-k-simple", "CODING", null, "POLAR", "BSC", List.of("polar")));

        TemplateResolver.Resolution resolution = resolver.resolve(
                intent("OFDM 多径 CP 长度对 BER 的影响", "OFDM", null, "EPA", List.of("ofdm", "cp")),
                templates);

        assertEquals(TemplateResolver.Verdict.NO_MATCH, resolution.verdict(),
                "an OFDM intent must not match QPSK or polar templates");
    }

    @Test
    void ambiguousIntentListsAlternativesInsteadOfPicking() {
        TemplateResolver resolver = new TemplateResolver();
        List<ExperimentDefinition> templates = List.of(
                template("qpsk-v1", "MODULATION", "QPSK", null, "AWGN", List.of("qpsk")),
                template("qpsk-v2", "MODULATION", "QPSK", null, "AWGN", List.of("qpsk")));

        TemplateResolver.Resolution resolution =
                resolver.resolve(intent("QPSK BER", "QPSK", null, "AWGN", List.of("qpsk")), templates);

        assertEquals(TemplateResolver.Verdict.AMBIGUOUS, resolution.verdict());
        assertEquals(2, resolution.alternativeTemplates().size(),
                "multiple equally good templates must be offered, not silently picked");
    }

    @Test
    void llmTemplateIdIsNormalizedByTheBackend() {
        assertEquals("qpsk-awgn-ber", DashScopeTemplateGenerationModel.normalizeSlug("QPSK AWGN BER"));
        assertEquals("qpsk-awgn-ber", DashScopeTemplateGenerationModel.normalizeSlug(" QPSK_AWGN_BER!! "));
        assertEquals("ofdm-cp-length-study",
                DashScopeTemplateGenerationModel.normalizeSlug("OFDM CP Length Study"));
        assertEquals("experiment-template", DashScopeTemplateGenerationModel.normalizeSlug(""));
        assertTrue(DashScopeTemplateGenerationModel.normalizeSlug("a".repeat(60)).length() <= 40,
                "template ids must be length-bounded");
    }
}
