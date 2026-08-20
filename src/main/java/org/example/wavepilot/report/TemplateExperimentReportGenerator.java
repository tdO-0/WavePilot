package org.example.wavepilot.report;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TemplateExperimentReportGenerator {

    public ExperimentReportDocument generate(ExperimentReportData data) {
        return new ExperimentReportDocument(data.jobId(), CitationStatus.VERIFIED, "TEMPLATE",
                markdown(data), data, data.conclusions(), data.citations(), Instant.now());
    }

    String markdown(ExperimentReportData data) {
        StringBuilder out = new StringBuilder();
        out.append("# WavePilot 实验报告\n\n")
                .append("## 1. 实验目的\n\n验证 WavePilot 对极化码码维数识别实验的编排、执行、结果校验与可追溯报告链路。\n\n")
                .append("## 2. 实验配置\n\n")
                .append("- 码长：").append(data.codeLengths()).append(cite(data.configurationCitationIds().get("codeLengths"))).append("\n")
                .append("- BSC errorRate：").append(data.errorRateRange().start()).append(" 至 ")
                .append(data.errorRateRange().end()).append("，步长 ").append(data.errorRateRange().step())
                .append(cite(data.configurationCitationIds().get("errorRateStart"),
                        data.configurationCitationIds().get("errorRateEnd"),
                        data.configurationCitationIds().get("errorRateStep"))).append("\n")
                .append("- 每次截获完整码字 M=").append(data.sampleCount())
                .append(cite(data.configurationCitationIds().get("sampleCount"))).append("\n")
                .append("- 每参数点独立重复 T=").append(data.monteCarloTimes())
                .append(cite(data.configurationCitationIds().get("monteCarloTimes"))).append("\n")
                .append("- randomSeed=").append(data.randomSeed())
                .append(cite(data.configurationCitationIds().get("randomSeed"))).append("\n\n")
                .append("## 3. 算法说明\n\n")
                .append("算法 `").append(data.algorithmName()).append("`（版本 ").append(data.algorithmVersion())
                .append("）是基于 BSC、逆向极化变换和二项似然判决的简化极化码码维数识别基线。errorRate 表示 BSC 比特翻转概率，不是 SNR。\n\n")
                .append("## 4. 执行环境\n\n")
                .append(executionEnvironment(data)).append("\n\n")
                .append("## 5. 实验结果摘要\n\n")
                .append("- 最小准确率：").append(data.accuracySummary().minAccuracy())
                .append(cite(data.accuracySummary().minCitationId())).append("\n")
                .append("- 最大准确率：").append(data.accuracySummary().maxAccuracy())
                .append(cite(data.accuracySummary().maxCitationId())).append("\n")
                .append("- 平均准确率：").append(data.accuracySummary().meanAccuracy())
                .append(cite(data.accuracySummary().meanCitationId())).append("\n\n")
                .append("## 6. 参数趋势\n\n")
                .append("| N | BSC ε | Accuracy | meanEstimatedK | MAE | Bias |\n")
                .append("|---:|---:|---:|---:|---:|---:|\n");
        for (ExperimentReportData.AccuracyPoint point : data.accuracyPoints()) {
            out.append("| ").append(point.codeLength()).append(cite(point.citationIds().get("codeLength")))
                    .append(" | ").append(point.errorRate()).append(cite(point.citationIds().get("errorRate")))
                    .append(" | ").append(point.accuracy()).append(cite(point.citationIds().get("accuracy")))
                    .append(" | ").append(point.meanEstimatedK()).append(cite(point.citationIds().get("meanEstimatedK")))
                    .append(" | ").append(point.mae()).append(cite(point.citationIds().get("mae")))
                    .append(" | ").append(point.bias()).append(cite(point.citationIds().get("bias"))).append(" |\n");
        }
        out.append("\n每个码长的最佳/最差点由 Java 对上述 CSV 行按 Accuracy 确定，不由模型计算。\n\n")
                .append("## 7. 边界与异常\n\n本次数据仅覆盖已列出的参数网格；报告不会外推未执行参数点，也不会把 errorRate 解释为 SNR。\n\n")
                .append("## 8. Artifact 来源\n\n");
        data.artifacts().forEach(artifact -> out.append("- ").append(artifact.artifactType()).append(": `")
                .append(artifact.relativePath()).append("`，SHA-256=`").append(artifact.sha256()).append("`\n"));
        out.append("\n## 9. 可复现信息\n\n固定模板 `").append(data.templateVersion())
                .append("`，randomSeed=").append(data.randomSeed())
                .append(cite(data.configurationCitationIds().get("randomSeed")))
                .append("，总参数点=").append(data.totalPoints())
                .append(cite(data.configurationCitationIds().get("totalPoints"))).append("。\n\n")
                .append("## 10. 算法真实性声明\n\n")
                .append("classification=").append(data.classification()).append("，algorithmValidated=")
                .append(data.algorithmValidated()).append("。结果用于验证 WavePilot 实验编排与可追溯链路，")
                .append("不能作为论文复现结果、用户创新算法或科研性能结论。\n");
        return out.toString();
    }

    private String executionEnvironment(ExperimentReportData data) {
        if (data.mock()) {
            return "本实验由内置确定性 Mock Runner 执行，未启动 MATLAB；runnerType="
                    + data.runnerType() + "，mock=true。";
        }
        return "本实验由本地 MATLAB " + data.matlabVersion() + " 执行；runnerType="
                + data.runnerType() + "，mock=false。";
    }

    private String cite(String... ids) {
        return " [" + String.join(", ", ids) + "]";
    }
}
