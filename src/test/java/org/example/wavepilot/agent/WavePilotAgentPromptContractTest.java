package org.example.wavepilot.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WavePilotAgentPromptContractTest {

    @Test
    void promptContainsRequiredSafetyAndTruthfulnessRules() {
        String prompt = WavePilotAgentPrompt.SYSTEM_PROMPT;
        assertTrue(prompt.contains("通信仿真实验助手"));
        assertTrue(prompt.contains("必须追问"));
        assertTrue(prompt.contains("Java 校验失败时不得创建任务"));
        assertTrue(prompt.contains("ExperimentService"));
        assertTrue(prompt.contains("ProcessBuilder"));
        assertTrue(prompt.contains("MATLAB"));
        assertTrue(prompt.contains("模拟数据"));
        assertTrue(prompt.contains("不得虚构"));
        assertTrue(prompt.contains("KB[documentId/chunkId]"));
    }

    @Test
    void promptGuidesTemplateToolUsageAndApprovalBoundary() {
        String prompt = WavePilotAgentPrompt.SYSTEM_PROMPT;
        assertTrue(prompt.contains("generateTemplateCandidate"), "template generation tool must be named");
        assertTrue(prompt.contains("validateTemplateCandidate"), "validation tool must be named");
        assertTrue(prompt.contains("requestTemplateSmoke"), "smoke tool must be named");
        assertTrue(prompt.contains("你无权批准、激活或发布模板"),
                "the agent boundary must be explicit in the prompt");
        assertTrue(prompt.contains("listExperimentTemplates"), "catalog query tools must be named");
        assertTrue(prompt.contains("不要用 createExperimentSpec 解析极化码之外的实验类型"),
                "spec parsing must stay polar-only");
    }
}
