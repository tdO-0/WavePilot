package org.example.wavepilot.report;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production grounded analysis model. It receives only the {@link GroundedAnalysisContext}
 * (validated data + citations) and produces natural-language analysis. The prompt forbids
 * inventing numbers; the post-generation validator enforces it: any numeric token in the
 * output must already exist in the context, and every numeric conclusion must carry a
 * citation.
 */
@Component
@ConditionalOnProperty(prefix = "wavepilot", name = "report-language.mode", havingValue = "llm")
public class DashScopeReportLanguageModel implements GroundedReportLanguageModel {

    public static final String NAME = "dashscope-report-language";

    private final ChatModel chatModel;

    public DashScopeReportLanguageModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AnalysisDraft analyze(GroundedAnalysisContext context) {
        String prompt = """
                实验类型：%s
                模板：%s（版本 %s）
                参数：%s
                维度：%s
                指标：%s
                聚合值：%s
                数据行（每行：维度值 + 指标值 + 指标名）：
                %s
                Citations：%s

                请用中文给出实验分析（markdown 格式），必须包含：
                1. 趋势：主维度（如 Eb/N0、删除概率、SNR）变化时指标（如 BER）如何变化，只依据上述数据；
                2. 异常点或转折点（如有）；
                3. 理论/仿真差异（如果数据里同时有仿真与理论列）；
                4. 下一步建议（如增加参数点、扩大范围、调整符号数等）。
                硬性约束：
                - 只能引用上述数据中出现的数值，禁止编造任何数字；
                - 每个明确数值结论必须标注 Citation id（如 [CIT-xxx]）；
                - 禁止声称算法已科学验证（algorithmValidated=false 必须保留）；
                - 趋势推断不得表述为已验证事实。
                """.formatted(
                context.experimentTypeId(), context.templateId(), context.templateVersion(),
                context.parameters(), context.dimensions(), context.metrics(), context.aggregates(),
                seriesText(context), citationText(context));
        String output = chatModel.call(new Prompt(List.of(
                new SystemMessage(ANALYSIS_PROMPT),
                new UserMessage(prompt)))).getResult().getOutput().getText();
        return new AnalysisDraft(output, List.of());
    }

    private String seriesText(GroundedAnalysisContext context) {
        StringBuilder out = new StringBuilder();
        for (ExperimentResultData.MetricSeries series : context.series()) {
            out.append(series.dimensionValues()).append(" -> ").append(series.metricValues())
                    .append(" citations=").append(series.citationIds()).append('\n');
        }
        return out.toString();
    }

    private String citationText(GroundedAnalysisContext context) {
        StringBuilder out = new StringBuilder();
        for (ArtifactCitation citation : context.citations()) {
            out.append(citation.citationId()).append(": ").append(citation.artifactId())
                    .append(" ").append(citation.fieldName()).append(" row ")
                    .append(citation.rowReference()).append(" = ").append(citation.value()).append('\n');
        }
        return out.toString();
    }

    private static final String ANALYSIS_PROMPT = """
            你是 WavePilot 的实验分析模型。你只能基于提供的实验数据与 Citation 做分析。
            数据之外任何数值都不存在；不得编造、外推或声称未验证的结论。
            algorithmValidated=false：任何趋势推断都只是观察，不是科学验证。
            """;
}
