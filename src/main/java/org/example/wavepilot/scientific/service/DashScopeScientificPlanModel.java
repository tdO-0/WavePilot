package org.example.wavepilot.scientific.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ScientificCapability;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Opt-in structured planner. It can select capabilities but cannot emit executable code. */
@Component
@ConditionalOnProperty(name = "wavepilot.scientific.planner-mode", havingValue = "model")
public class DashScopeScientificPlanModel implements ScientificPlanModel {
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DashScopeScientificPlanModel(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScientificPlanProposal propose(AgentRun run, int iteration,
                                          Set<ScientificCapability> registeredCapabilities) {
        String request = """
                goal=%s
                state=%s
                iteration=%d
                currentSpec=%s
                evidenceCount=%d
                registeredCapabilities=%s
                """.formatted(run.getGoal().description(), run.getState(), iteration,
                run.getCurrentSpec(), run.getRetrievedEvidence().size(), registeredCapabilities);
        String output = chatModel.call(new Prompt(List.of(
                new SystemMessage("""
                        Return only strict JSON {"capabilities":["..."]}.
                        Select only registered capabilities. RETRIEVE_EVIDENCE must precede
                        EXECUTE_VALIDATED_EXPERIMENT, which must precede VERIFY_GROUNDED_RESULT.
                        You cannot output Java, MATLAB, shell, paths, executables or custom tools.
                        """), new UserMessage(request)))).getResult().getOutput().getText();
        try {
            int start = output.indexOf('{');
            int end = output.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON object");
            JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
            if (root.size() != 1 || root.get("capabilities") == null
                    || !root.get("capabilities").isArray()) {
                throw new IllegalArgumentException("only capabilities array is allowed");
            }
            List<String> values = new ArrayList<>();
            root.get("capabilities").forEach(value -> values.add(value.asText()));
            return new ScientificPlanProposal(values);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid structured planner output", e);
        }
    }
}
