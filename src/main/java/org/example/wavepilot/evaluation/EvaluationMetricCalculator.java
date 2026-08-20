package org.example.wavepilot.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Computes every metric from the actual per-case execution results; nothing is hardcoded. */
@Component
public class EvaluationMetricCalculator {

    public List<EvaluationMetric> compute(List<EvaluationCaseResult> results) {
        Map<EvaluationCaseType, List<EvaluationCaseResult>> byType = new EnumMap<>(EvaluationCaseType.class);
        for (EvaluationCaseResult result : results) {
            byType.computeIfAbsent(result.caseType(), ignored -> new ArrayList<>()).add(result);
        }
        List<EvaluationMetric> metrics = new ArrayList<>();
        metrics.add(rate(byType, EvaluationCaseType.COMPLETE_SPEC, "specParseAccuracy", "Spec 字段解析正确率"));
        metrics.add(rate(byType, EvaluationCaseType.MISSING_PARAMETER, "missingParameterDetectionRate", "缺参识别率"));
        metrics.add(rate(byType, EvaluationCaseType.INVALID_PARAMETER, "invalidParameterBlockRate", "非法参数拦截率"));
        metrics.add(rate(byType, EvaluationCaseType.TOOL_SELECTION, "toolSelectionAccuracy", "工具选择正确率"));
        metrics.add(rate(byType, EvaluationCaseType.TOOL_SECURITY, "forbiddenToolBlockRate", "禁止工具调用拦截率"));
        metrics.add(rate(byType, EvaluationCaseType.JOB_SUBMISSION, "jobSubmissionSuccessRate", "Job 提交成功率"));
        metrics.add(rate(byType, EvaluationCaseType.KNOWLEDGE_RETRIEVAL, "knowledgeRetrievalRate", "知识检索命中率"));
        metrics.add(rate(byType, EvaluationCaseType.ARTIFACT_CITATION, "artifactCitationConsistencyRate", "Artifact 引用一致率"));
        metrics.add(rate(byType, EvaluationCaseType.REPORT_GROUNDING, "reportGroundingRate", "报告数值 Grounding 率"));
        metrics.add(rate(byType, EvaluationCaseType.REPLAY_CONSISTENCY, "replayConsistencyRate", "Replay 一致率"));
        long passed = results.stream().filter(EvaluationCaseResult::passed).count();
        metrics.add(new EvaluationMetric("overallTaskCompletionRate", "总任务完成率",
                passed, results.size(), ratio(passed, results.size())));
        return metrics;
    }

    private EvaluationMetric rate(Map<EvaluationCaseType, List<EvaluationCaseResult>> byType,
                                  EvaluationCaseType type, String name, String description) {
        List<EvaluationCaseResult> group = byType.getOrDefault(type, List.of());
        long passed = group.stream().filter(EvaluationCaseResult::passed).count();
        return new EvaluationMetric(name, description, passed, group.size(), ratio(passed, group.size()));
    }

    private double ratio(long passed, long total) {
        return total == 0 ? 0 : (double) passed / total;
    }
}
