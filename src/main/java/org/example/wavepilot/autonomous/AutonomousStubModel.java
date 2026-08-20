package org.example.wavepilot.autonomous;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted offline model for the autonomous loop: it walks the exact scripted flow and is
 * used when no ChatModel is configured (and by offline tests). It is a test double, never
 * a real planner; real operation uses {@link AutonomousChatModel}.
 */
public class AutonomousStubModel implements AutonomousModel {

    private static final Pattern CANDIDATE = Pattern.compile("候选已生成：(CAND-[A-Z0-9-]+)");
    private static final Pattern JOB = Pattern.compile("实验已提交：(JOB-[A-Z0-9-]+)");

    private final boolean withTemplates;
    private final String templateId;
    private final List<String> parameters;
    private final boolean analyzeResults;

    public AutonomousStubModel(boolean withTemplates, String templateId, List<String> parameters) {
        this(withTemplates, templateId, parameters, false);
    }

    public AutonomousStubModel(boolean withTemplates, String templateId, List<String> parameters,
                               boolean analyzeResults) {
        this.withTemplates = withTemplates;
        this.templateId = templateId;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.analyzeResults = analyzeResults;
    }

    @Override
    public String name() {
        return withTemplates ? "stub-autonomous-with-templates" : "stub-autonomous-no-templates";
    }

    @Override
    public String respond(List<String> history) {
        String joined = String.join("\n", history);
        if (!joined.contains("工具结果(searchTemplates)")) {
            return json("searchTemplates", Map.of("query", ""));
        }
        if (withTemplates) {
            // Natural-language parameter answer: a real model parses the answer and moves
            // on; the scripted stub cannot parse text, so it re-requests exact values.
            // Note: "用户填写参数" is a substring of the hangup marker, so distinguish the
            // structured submit by its "specJson" payload instead.
            if (joined.contains("用户补充参数") && !joined.contains("specJson")) {
                return json("requestParameterInput",
                        Map.of("templateId", templateId, "parameters", parameters));
            }
            if (!joined.contains("已挂起等待用户填写参数")) {
                return json("requestParameterInput",
                        Map.of("templateId", templateId, "parameters", parameters));
            }
            if (!joined.contains("工具结果(submitSpec)") && !joined.contains("实验已提交")) {
                return json("submitSpec", Map.of("specJson", ""));
            }
            String jobId = extract(JOB, joined);
            if (jobId != null && !joined.contains("实验 " + jobId + " 已成功")) {
                return json("waitForJobCompletion", Map.of("jobId", jobId));
            }
            if (jobId != null && !joined.contains("报告已生成")) {
                return json("generateReport", Map.of("jobId", jobId));
            }
            if (analyzeResults && !joined.contains("工具结果(analyzeResult)")) {
                return json("analyzeResult", Map.of("jobId", jobId));
            }
            return json("finish", Map.of("message",
                    analyzeResults ? "分析：实验 " + jobId + " 平均识别准确率最高，建议关注低信噪比区间。"
                            : "自主流程完成"));
        }
        if (!joined.contains("候选已生成")) {
            String request = history.isEmpty() ? "演示模板" : history.get(0);
            return json("generateCandidate", Map.of("request", request));
        }
        String candidateId = extract(CANDIDATE, joined);
        if (candidateId == null) return json("finish", Map.of("message", "候选生成失败"));
        if (!joined.contains("工具结果(validateCandidate)")) {
            return json("validateCandidate", Map.of("candidateId", candidateId));
        }
        if (!joined.contains("工具结果(smokeCandidate)")) {
            return json("smokeCandidate", Map.of("candidateId", candidateId));
        }
        if (!joined.contains("已挂起等待用户审批")) {
            return json("requestTemplateApproval", Map.of("candidateId", candidateId));
        }
        if (!joined.contains("用户已批准发布")) {
            return json("finish", Map.of("message", "等待用户审批"));
        }
        // 批准后先收集参数；用户填过之后（历史里已有挂起提示）就提交 Spec，不得再次挂起。
        // 自然语言补参后重新收集精确值（stub 无法解析文本，真实模型会直接理解）。
        // 结构化提交以 "specJson" 载荷区分（"用户填写参数"是挂起提示的子串）。
        if (joined.contains("用户补充参数") && !joined.contains("specJson")) {
            return json("requestParameterInput",
                    Map.of("candidateId", candidateId, "parameters", parameters));
        }
        if (!joined.contains("已挂起等待用户填写参数")) {
            return json("requestParameterInput",
                    Map.of("candidateId", candidateId, "parameters", parameters));
        }
        if (!joined.contains("工具结果(submitSpec)") && !joined.contains("实验已提交")) {
            return json("submitSpec", Map.of("specJson", ""));
        }
        String jobId = extract(JOB, joined);
        if (jobId != null && !joined.contains("实验 " + jobId + " 已成功")) {
            return json("waitForJobCompletion", Map.of("jobId", jobId));
        }
        if (jobId != null && !joined.contains("报告已生成")) {
            return json("generateReport", Map.of("jobId", jobId));
        }
        if (analyzeResults && !joined.contains("工具结果(analyzeResult)")) {
            return json("analyzeResult", Map.of("jobId", jobId));
        }
        return json("finish", Map.of("message",
                analyzeResults ? "分析：实验 " + jobId + " 平均识别准确率最高，建议关注低信噪比区间。"
                        : "自主流程完成"));
    }

    private String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String json(String tool, Map<String, Object> arguments) {
        StringBuilder builder = new StringBuilder("{\"tool\":\"").append(tool).append("\",\"arguments\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!first) builder.append(',');
            first = false;
            builder.append('"').append(entry.getKey()).append("\":\"")
                    .append(String.valueOf(entry.getValue()).replace("\"", "\\\"")).append('"');
        }
        return builder.append("}}").toString();
    }
}
