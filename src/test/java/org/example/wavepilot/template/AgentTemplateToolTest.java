package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.agent.WavePilotAgentTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent may generate and inspect candidates but never approve, activate or delete. */
class AgentTemplateToolTest {

    @Test
    void agentExposesTheSevenTemplateTools() {
        Set<String> tools = toolNames();
        for (String expected : List.of("listExperimentTemplates", "getExperimentTemplate",
                "listTemplateCandidates", "getTemplateCandidate", "generateTemplateCandidate",
                "validateTemplateCandidate", "requestTemplateSmoke")) {
            assertTrue(tools.contains(expected), "missing agent tool: " + expected);
        }
    }

    @Test
    void agentHasNoApprovalActivationOrDeletionTools() {
        Set<String> tools = toolNames();
        assertFalse(tools.contains("approveTemplateCandidate"), "approval must stay a user action");
        assertFalse(tools.contains("activateTemplate"), "activation must stay a user action");
        assertFalse(tools.contains("deleteTemplate"), "deletion is not exposed at all");
        assertFalse(tools.contains("publishTemplateCandidate"), "publishing must stay a user action");
        assertFalse(tools.contains("rejectTemplateCandidate"), "rejection must stay a user action");
    }

    @Test
    void candidateViewsNeverExposeTemplateFileContentsToTheAgent() throws Exception {
        Set<String> tools = toolNames();
        Method generate = Arrays.stream(WavePilotAgentTools.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("generateTemplateCandidate"))
                .findFirst().orElseThrow();
        assertTrue(tools.contains("generateTemplateCandidate"));
        // The tool signature only takes the request text; it cannot reach files directly.
        assertEquals(1, generate.getParameterCount());
    }

    private Set<String> toolNames() {
        return Arrays.stream(WavePilotAgentTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .map(Tool::name)
                .collect(Collectors.toSet());
    }
}
