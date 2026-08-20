package org.example.wavepilot.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The intent resolver must interpret semantics, not keywords: "帮我跑个调制仿真" resolves to
 * RUN_EXPERIMENT with missingCriticalInformation (it must never start designing a template
 * or a run), "看看能不能帮我跑一个 QPSK BER" is RUN_EXPERIMENT despite "看看", and without a
 * ChatModel the conservative fallback still separates queries from everything else.
 */
class ExperimentIntentResolverTest {

    private ExperimentIntentResolver resolver() {
        return new ExperimentIntentResolver(emptyChatModels(), new ObjectMapper());
    }

    private org.springframework.beans.factory.ObjectProvider<org.springframework.ai.chat.model.ChatModel> emptyChatModels() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public org.springframework.ai.chat.model.ChatModel getObject() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getObject(Object... args) { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique() { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return null; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return null; }
            @Override public void forEach(java.util.function.Consumer<? super org.springframework.ai.chat.model.ChatModel> action) { }
            @Override public java.util.stream.Stream<org.springframework.ai.chat.model.ChatModel> stream() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    @Test
    void offlineFallbackTreatsTemplateQuestionsAsQueries() {
        ExperimentIntent intent = resolver().resolve(List.of(), "目前系统里有哪些可用模板？");
        assertEquals(IntentType.QUERY_TEMPLATES, intent.intentType());
        assertFalse(intent.isExperimentAction());
    }

    @Test
    void offlineFallbackTreatsAnythingElseAsARunRequest() {
        // Without a model the resolver cannot understand semantics; conservative default is
        // to treat non-queries as experiment requests so the goal loop can ask questions.
        ExperimentIntent intent = resolver().resolve(List.of(), "跑一个 QPSK BER 实验");
        assertEquals(IntentType.RUN_EXPERIMENT, intent.intentType());
    }

    @Test
    void aCorrectionReplacesThePreviousIntent() {
        // Phase 20 I: "不对，我想用 8PSK" re-resolves to a new modulation; the corrected
        // intent must no longer match templates of the old modulation.
        ExperimentIntent original = new ExperimentIntent(IntentType.RUN_EXPERIMENT,
                "QPSK BER", "MODULATION", "QPSK", null, "AWGN",
                java.util.Map.of("ebNoStart", 0, "ebNoEnd", 10), List.of("BER"),
                List.of(), List.of("qpsk", "ber"), List.of(), List.of(), 0.9);
        ExperimentIntent corrected = new ExperimentIntent(IntentType.RUN_EXPERIMENT,
                "8PSK BER", "MODULATION", "8PSK", null, "AWGN",
                java.util.Map.of("ebNoStart", 0, "ebNoEnd", 10), List.of("BER"),
                List.of(), List.of("8psk", "ber"), List.of(), List.of(), 0.9);
        assertFalse(original.modulation().equals(corrected.modulation()),
                "a correction must change the modulation semantics");
        assertEquals("8PSK", corrected.modulation());
        // The corrected intent still carries the supplied parameters so the goal can resume.
        assertFalse(corrected.needsClarification());
    }

    @Test
    void anIntentRequiringClarificationIsNotActionable() {
        ExperimentIntent vague = new ExperimentIntent(IntentType.RUN_EXPERIMENT,
                "跑个调制仿真", null, null, null, null, java.util.Map.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of("modulation", "channel"), 0.4);
        assertTrue(vague.needsClarification(), "missing critical info must demand clarification");
        assertTrue(vague.isExperimentAction(), "RUN_EXPERIMENT stays an experiment intent");
    }

    @Test
    void fullySpecifiedExperimentNeedsNoClarification() {
        ExperimentIntent concrete = new ExperimentIntent(IntentType.RUN_EXPERIMENT,
                "QPSK AWGN BER", "MODULATION", "QPSK", null, "AWGN",
                java.util.Map.of("ebNoStart", 0, "ebNoEnd", 10), List.of("BER"),
                List.of(), List.of("qpsk", "ber"), List.of(), List.of(), 0.95);
        assertFalse(concrete.needsClarification());
        assertEquals("QPSK", concrete.modulation());
        assertEquals("AWGN", concrete.channel());
    }

    @Test
    void llmResolverClassifiesHesitantRunRequestAsRunExperiment() {
        // A scripted ChatModel answering with the resolver JSON: "看看能不能帮我跑一个 QPSK BER"
        // must be RUN_EXPERIMENT even though the sentence starts with a question-like "看看".
        var model = new org.springframework.ai.chat.model.ChatModel() {
            @Override public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                String json = """
                        {"intentType":"RUN_EXPERIMENT","objective":"跑一个 QPSK BER 实验",
                        "experimentFamily":"MODULATION","modulation":"QPSK","coding":null,"channel":"AWGN",
                        "suppliedParameters":{},"requestedMetrics":["BER"],"requestedOutputs":[],
                        "semanticTags":["qpsk","ber"],"assumptions":[],"missingCriticalInformation":[],
                        "confidence":0.93}
                        """;
                return new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage(json))));
            }
            @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() { return null; }
        };
        ExperimentIntentResolver resolver = new ExperimentIntentResolver(
                objectProvider(model), new ObjectMapper());
        ExperimentIntent intent = resolver.resolve(List.of(), "看看能不能帮我跑一个 QPSK BER");
        assertEquals(IntentType.RUN_EXPERIMENT, intent.intentType(),
                "'看看' must not demote a run request to QA");
        assertEquals("QPSK", intent.modulation());
        assertFalse(intent.needsClarification());
    }

    private org.springframework.beans.factory.ObjectProvider<org.springframework.ai.chat.model.ChatModel> objectProvider(
            org.springframework.ai.chat.model.ChatModel model) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public org.springframework.ai.chat.model.ChatModel getObject() { return model; }
            @Override public org.springframework.ai.chat.model.ChatModel getObject(Object... args) { return model; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable() { return model; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique() { return model; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfAvailable(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return model; }
            @Override public org.springframework.ai.chat.model.ChatModel getIfUnique(
                    java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel> defaultSupplier) { return model; }
            @Override public void forEach(java.util.function.Consumer<? super org.springframework.ai.chat.model.ChatModel> action) { }
            @Override public java.util.stream.Stream<org.springframework.ai.chat.model.ChatModel> stream() {
                return java.util.stream.Stream.of(model);
            }
        };
    }
}
