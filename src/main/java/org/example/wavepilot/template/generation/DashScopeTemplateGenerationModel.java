package org.example.wavepilot.template.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.intent.ExperimentIntent;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Production template-generation model: a real ChatModel designs the candidate package from
 * the resolved experiment intent. The template id, parameter schema, output columns and
 * metrics follow the experiment semantics — a QPSK BER request designs a BER schema, an
 * OFDM CP-length study designs a completely different schema. The backend still normalizes
 * the templateId, enforces limits and validates the definition; the model never writes
 * anything itself.
 */
@Component
@ConditionalOnProperty(prefix = "wavepilot", name = "template-generation.mode", havingValue = "llm")
public class DashScopeTemplateGenerationModel implements TemplateGenerationModel {

    public static final String NAME = "dashscope-template-gen";

    private static final Pattern SLUG = Pattern.compile("[^a-z0-9-]");
    private static final int MAX_ID_LENGTH = 40;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DashScopeTemplateGenerationModel(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TemplateGenerationResult generate(String request) {
        // Legacy plain-text entry; delegates to a minimal design request.
        return generate(new ExperimentTemplateDesignRequest(
                new ExperimentIntent(org.example.wavepilot.intent.IntentType.CREATE_TEMPLATE,
                        request, null, null, null, null, Map.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 0.5),
                request, List.of(), List.of(), List.of()));
    }

    @Override
    public TemplateGenerationResult generate(ExperimentTemplateDesignRequest request) {
        ExperimentIntent intent = request.intent();
        String prompt = designPrompt(intent, request);
        String output = chatModel.call(new Prompt(List.of(
                new SystemMessage(TEMPLATE_DESIGN_PROMPT),
                new UserMessage(prompt)))).getResult().getOutput().getText();
        try {
            return parseDesign(output, request);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "TEMPLATE_GENERATION_MODEL_UNAVAILABLE: 模板设计模型输出无法解析: " + e.getMessage()
                            + "；原始输出: " + truncate(output));
        }
    }

    private TemplateGenerationResult parseDesign(String output, ExperimentTemplateDesignRequest request)
            throws Exception {
        String json = extractJson(output);
        JsonNode root = objectMapper.readTree(json);
        String proposed = text(root, "templateId");
        String templateId = normalizeSlug(proposed);
        String experimentTypeId = text(root, "experimentTypeId");
        String displayName = text(root, "displayName");
        String description = text(root, "description");
        String definitionYaml = text(root, "definitionYaml");
        String manifestJson = text(root, "manifestJson");
        List<String> assumptions = strings(root, "assumptions");
        List<String> unresolved = strings(root, "unresolvedQuestions");
        List<GeneratedFile> files = new ArrayList<>();
        JsonNode fileNodes = root.get("files");
        if (fileNodes != null && fileNodes.isArray()) {
            for (JsonNode file : fileNodes) {
                files.add(new GeneratedFile(text(file, "relativePath"), text(file, "content")));
            }
        }
        return new TemplateGenerationResult(templateId, experimentTypeId, displayName, "1.0.0",
                description, definitionYaml, manifestJson, files,
                "DashScope 模板设计模型按实验语义生成；后端完成规范化与校验",
                assumptions, unresolved);
    }

    /** Lowercase slug, whitelisted characters, bounded length; backend decides the final id. */
    public static String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) return "experiment-template";
        String slug = SLUG.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = slug.replaceAll("-{2,}", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > MAX_ID_LENGTH) slug = slug.substring(0, MAX_ID_LENGTH);
        return slug.isBlank() ? "experiment-template" : slug;
    }

    private String designPrompt(ExperimentIntent intent, ExperimentTemplateDesignRequest request) {
        return """
                用户原始请求：%s

                已解析意图：
                - 目标：%s
                - 实验族：%s
                - 调制：%s
                - 编码：%s
                - 信道：%s
                - 期望指标：%s
                - 期望输出：%s
                - 已提供参数：%s

                请根据这些实验语义设计模板包（JSON 输出）。参数 Schema、输出列、指标必须与
                实验类型匹配：例如 OFDM 多径 CP 长度研究需要 fftSize/cpLengths/snrStart/snrEnd/
                snrStep/channelModel/frames，而不是 AWGN BER 的 ebNo 系列参数。
                """.formatted(
                request.userRequest(), nullSafe(intent.objective()), nullSafe(intent.experimentFamily()),
                nullSafe(intent.modulation()), nullSafe(intent.coding()), nullSafe(intent.channel()),
                intent.requestedMetrics(), intent.requestedOutputs(), intent.suppliedParameters());
    }

    private static final String TEMPLATE_DESIGN_PROMPT = """
            你是 WavePilot 的模板设计模型。根据用户实验意图设计一个完整的声明式模板包，
            只输出 JSON，不要附加解释。

            JSON 结构：
            {
              "templateId": "短横线小写英文标识（如 qpsk-awgn-ber / ofdm-cp-length-study）",
              "experimentTypeId": "同上或略扩展",
              "displayName": "中文显示名",
              "description": "一句话描述",
              "definitionYaml": "完整 experiment-definition.yaml 文本（YAML 字符串）",
              "manifestJson": "完整 manifest.json 文本（JSON 字符串）",
              "files": [
                { "relativePath": "matlab/run_experiment.m", "content": "MATLAB 脚本，读取 matlab-input.json 的 customParameters，输出 accuracy.csv（带仿真指标列）与 summary.json（experimentType/algorithmName/rowCount）与 run.log" }
              ],
              "assumptions": ["...", "..."],
              "unresolvedQuestions": ["...", "..."]
            }

            YAML 规则：
            - 字段：templateId, experimentTypeId, displayName, version, entryPoint,
              description, parameters, outputs, metrics, replay, algorithm, capabilities
            - parameters 每项：name, type(STRING|INTEGER|NUMBER|BOOLEAN|ENUM),
              required, defaultValue, min, max, enumValues, sweep, step, description, unit
            - 有 sweep 维度（如 snrStart/snrEnd/snrStep 或 ebNoStart/ebNoEnd/ebNoStep）时，
              start/end/step 三个参数都标记 sweep=true，step 给默认值
            - outputs: csvFile=accuracy.csv, requiredColumns（第一个是主扫描维度列）,
              numericColumns, rejectNonFinite, columnBounds, jsonRequiredFields,
              requiredArtifacts
            - metrics: metricName, displayName, unit, sourceColumn, aggregation(MEAN|MIN|MAX)
            - replay: comparisonColumn, maxAbsoluteTolerance, meanAbsoluteTolerance,
              compareMean, required
            - algorithm: name, version, classification(SIMULATION_BASELINE),
              algorithmValidated 必须为 false
            - capabilities: experimentFamily, objective, modulation, coding, channel, tags, aliases
            - 参数 Schema 必须与实验类型匹配，禁止套用其他实验的参数
            """;

    private String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON object in model output");
        return output.substring(start, end + 1);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private List<String> strings(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        JsonNode value = node.get(field);
        if (value != null && value.isArray()) {
            for (JsonNode item : value) result.add(item.asText());
        }
        return result;
    }

    private String nullSafe(String value) {
        return value == null ? "（未提供）" : value;
    }

    private String truncate(String text) {
        return text == null || text.length() <= 300 ? String.valueOf(text) : text.substring(0, 300) + "…";
    }
}
