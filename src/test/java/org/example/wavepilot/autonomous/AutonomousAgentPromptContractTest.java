package org.example.wavepilot.autonomous;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent's analysis instruction must demand a conclusion AND an actionable recommendation. */
class AutonomousAgentPromptContractTest {

    @Test
    void analysisInstructionDemandsConclusionsAndRecommendations() {
        String prompt = AutonomousAgentPrompt.SYSTEM_PROMPT;
        assertTrue(prompt.contains("结论"), "analysis must demand a conclusion section");
        assertTrue(prompt.contains("建议"), "analysis must demand recommendations");
        assertTrue(prompt.contains("趋势"), "conclusion must cover trends");
        assertTrue(prompt.contains("异常"), "conclusion must cover anomalies");
    }

    @Test
    void theStubModelAnalysisCarriesAnActionableRecommendation() {
        // The stub walks the scripted flow with analyzeResults=true; its finish message
        // must demonstrate the conclusion + recommendation contract for offline runs.
        // History mirrors what the tool executor would have written for each step.
        AutonomousStubModel model = new AutonomousStubModel(true, "tpl",
                java.util.List.of("codeLengths"), true);
        String answer = model.respond(java.util.List.of(
                "工具结果(searchTemplates)",
                "已挂起等待用户填写参数",
                "工具结果(submitSpec)",
                "实验已提交：JOB-ABC",
                "实验 JOB-ABC 已成功",
                "报告已生成",
                "工具结果(analyzeResult)"));
        assertTrue(answer.contains("finish"), "stub must finish after analysis, got: " + answer);
        assertTrue(answer.contains("建议"), "stub analysis must contain a recommendation");
    }
}
