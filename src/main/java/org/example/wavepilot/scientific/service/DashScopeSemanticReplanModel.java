package org.example.wavepilot.scientific.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Opt-in semantic proposal; the returned spec still passes Java bounds and schema gates. */
@Component
@ConditionalOnProperty(name = "wavepilot.scientific.replanner-mode", havingValue = "model")
public class DashScopeSemanticReplanModel implements SemanticReplanModel {
    private static final Set<String> SPEC_FIELDS = Set.of("experimentType", "codeLengths",
            "errorRateStart", "errorRateEnd", "errorRateStep", "sampleCount",
            "monteCarloTimes", "randomSeed", "outputTypes", "description",
            "experimentTypeId", "customParameters");
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DashScopeSemanticReplanModel(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExperimentSpec propose(SemanticReplanContext context) {
        String prompt = """
                goal=%s
                currentSpec=%s
                parameterBounds=%s
                observation=%s
                verification=%s
                evidence=%s
                previousChanges=%s

                Return one complete ExperimentSpec JSON object. Change only bounded parameters.
                """.formatted(context.goal().description(), context.currentSpec(),
                context.goal().parameterBounds(), context.observation(), context.verification(),
                context.retrievedEvidence(), context.previousChanges());
        String output = chatModel.call(new Prompt(List.of(new SystemMessage("""
                You propose a bounded WavePilot ExperimentSpec. Return strict JSON only.
                Never emit Java, MATLAB, shell, an executable, a path, or a tool call.
                Java will reject any field, value, parameter step or budget violation.
                """), new UserMessage(prompt)))).getResult().getOutput().getText();
        try {
            int start = output.indexOf('{');
            int end = output.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON object");
            String json = output.substring(start, end + 1);
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) throw new IllegalArgumentException("ExperimentSpec must be an object");
            Set<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!SPEC_FIELDS.equals(actual)) {
                throw new IllegalArgumentException("complete ExperimentSpec fields are required; unknown fields are forbidden");
            }
            return objectMapper.treeToValue(root, ExperimentSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid semantic replan output", e);
        }
    }
}
