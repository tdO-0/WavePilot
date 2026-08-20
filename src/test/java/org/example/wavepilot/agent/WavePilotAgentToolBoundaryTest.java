package org.example.wavepilot.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WavePilotAgentToolBoundaryTest {

    @Test
    void exposesExactlyTheSeventeenControlledTools() {
        Set<String> actual = Stream.of(WavePilotAgentTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .map(Tool::name).collect(Collectors.toSet());
        assertEquals(Set.of("searchExperimentKnowledge", "createExperimentSpec", "validateExperimentSpec",
                "createExperimentPlan", "submitExperiment", "getExperimentStatus", "cancelExperiment",
                "listExperimentArtifacts", "readExperimentSummary", "compareExperiments",
                "listExperimentTemplates", "getExperimentTemplate", "listTemplateCandidates",
                "getTemplateCandidate", "generateTemplateCandidate", "validateTemplateCandidate",
                "requestTemplateSmoke"), actual);
    }

    @Test
    void hasNoRunnerRepositoryProcessOrFileSystemDependency() {
        for (Field field : WavePilotAgentTools.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.contains("Repository"), type);
            assertFalse(type.contains("Runner"), type);
            assertFalse(type.equals("java.lang.ProcessBuilder"), type);
            assertFalse(type.startsWith("java.nio.file"), type);
            assertFalse(type.equals("java.io.File"), type);
        }
        for (Method method : WavePilotAgentTools.class.getDeclaredMethods()) {
            assertFalse(method.getReturnType().getName().contains("Repository"));
            assertFalse(method.getReturnType().getName().contains("Runner"));
        }
    }
}
