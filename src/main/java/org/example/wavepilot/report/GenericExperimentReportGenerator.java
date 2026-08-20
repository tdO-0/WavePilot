package org.example.wavepilot.report;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic report generator for declarative (generic) experiments. The report text
 * follows the template's own semantics: a QPSK AWGN BER template produces a QPSK/AWGN/Eb-N0/
 * BER report (never polar code-length language), a BEC template produces erasure-probability
 * language, an OFDM CP-length template produces FFT/CP/SNR language. All numbers come from
 * the validated {@link ExperimentResultData} (itself built from the validated CSV), so
 * every value is grounded and cited.
 */
@Component
public class GenericExperimentReportGenerator {

    public ExperimentReportDocument generate(ExperimentResultData data) {
        return new ExperimentReportDocument(data.jobId(), CitationStatus.VERIFIED, "TEMPLATE",
                markdown(data), null, data.conclusions(), data.citations(), Instant.now());
    }

    String markdown(ExperimentResultData data) {
        String type = data.experimentTypeId() == null ? "" : data.experimentTypeId().toLowerCase(Locale.ROOT);
        boolean qpskLike = type.contains("qpsk") || type.contains("psk") || type.contains("ber");
        boolean becLike = type.contains("bec") || type.contains("erasure");
        String modulation = qpskLike ? "QPSK" : data.parameters().getOrDefault("modulation", "模板定义调制").toString();
        String channel = data.parameters().getOrDefault("channel", "AWGN").toString();
        String metricName = data.metrics().isEmpty() ? "BER" : data.metrics().get(0);
        String dimensionName = data.dimensions().isEmpty() ? "参数" : data.dimensions().get(0);

        StringBuilder out = new StringBuilder();
        out.append("# WavePilot 实验报告\n\n")
                .append("## 1. 实验目的\n\n")
                .append("验证模板 `").append(data.templateId()).append("` 下，")
                .append(modulation).append(" 在 ").append(channel).append(" 信道中的 ")
                .append(metricName).append(" 仿真与可追溯报告链路。\n\n")
                .append("## 2. 实验配置\n\n");
        for (Map.Entry<String, Object> entry : data.parameters().entrySet()) {
            out.append("- ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }
        out.append("- 总参数点：").append(data.series().size()).append("\n\n")
                .append("## 3. 算法说明\n\n")
                .append("算法 `").append(data.algorithmName()).append("`（版本 ")
                .append(data.algorithmVersion()).append("）。classification=")
                .append(data.classification()).append("，algorithmValidated=")
                .append(data.algorithmValidated()).append("。\n\n")
                .append("## 4. 执行环境\n\n")
                .append("runnerType=").append(data.runnerType())
                .append("，mock=").append(data.mock())
                .append("，MATLAB ").append(data.matlabVersion() == null ? "" : data.matlabVersion())
                .append("。\n\n")
                .append("## 5. 实验结果\n\n")
                .append("| ").append(dimensionName).append(" | ").append(String.join(" | ", data.metrics())).append(" |\n")
                .append("|---:|---:|\n");
        for (ExperimentResultData.MetricSeries series : data.series()) {
            out.append("| ");
            List<String> cells = new java.util.ArrayList<>();
            for (Map.Entry<String, Object> dim : series.dimensionValues().entrySet()) {
                cells.add(String.valueOf(dim.getValue()) + cite(series.citationIds(), dim.getKey()));
            }
            for (Map.Entry<String, Double> metric : series.metricValues().entrySet()) {
                cells.add(String.format(Locale.ROOT, "%.6f", metric.getValue())
                        + cite(series.citationIds(), metric.getKey()));
            }
            out.append(String.join(" | ", cells)).append(" |\n");
        }
        out.append("\n聚合：");
        for (Map.Entry<String, Double> agg : data.aggregates().entrySet()) {
            out.append(" ").append(agg.getKey()).append("=")
                    .append(String.format(Locale.ROOT, "%.6f", agg.getValue())).append(";");
        }
        out.append("\n\n").append("## 6. 边界与异常\n\n")
                .append("数据仅覆盖已列出的参数网格；本报告不外推未执行参数点。")
                .append(becLike ? " 本实验研究删除概率 ε 对 " + metricName + " 的影响。"
                        : qpskLike ? " 本实验研究 Eb/N0 对 " + metricName + " 的影响。"
                        : " 指标由模板定义驱动。")
                .append("\n\n").append("## 7. Artifact 来源\n\n");
        data.artifacts().forEach(artifact -> out.append("- ").append(artifact.artifactType())
                .append(": `").append(artifact.relativePath()).append("`，SHA-256=`")
                .append(artifact.sha256()).append("`\n"));
        out.append("\n## 8. 算法真实性声明\n\n")
                .append("classification=").append(data.classification()).append("，algorithmValidated=")
                .append(data.algorithmValidated()).append("。结果用于验证 WavePilot 实验编排与可追溯链路，")
                .append("不能作为论文复现结果、用户创新算法或科研性能结论。\n");
        return out.toString();
    }

    private String cite(Map<String, String> citationIds, String field) {
        String id = citationIds.get(field);
        return id == null ? "" : " [" + id + "]";
    }
}
