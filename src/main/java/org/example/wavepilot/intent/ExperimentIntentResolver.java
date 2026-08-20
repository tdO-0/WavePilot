package org.example.wavepilot.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real intent resolution. The message is passed to the configured ChatModel which returns a
 * structured JSON map that is mapped onto {@link ExperimentIntent}. No keyword routing:
 * "看看能不能帮我跑一个 QPSK BER" is RUN_EXPERIMENT, "现在有什么模板？" is QUERY_TEMPLATES,
 * and "帮我跑个调制仿真" resolves to RUN_EXPERIMENT with missingCriticalInformation populated
 * so the Agent asks before designing anything.
 *
 * <p>When no ChatModel is available the resolver degrades to a conservative deterministic
 * fallback that only distinguishes obvious queries from everything else; the real semantic
 * path is the LLM one.
 */
@Component
public class ExperimentIntentResolver {

    private static final String RESOLVER_PROMPT = """
            你是 WavePilot 的意图理解组件。把用户的实验相关请求解析为 JSON，只输出 JSON，不要附加解释。

            输出结构：
            {
              "intentType": "GENERAL_QA|QUERY_TEMPLATES|RUN_EXPERIMENT|CREATE_TEMPLATE|ANALYZE_RESULT|REPLAY_EXPERIMENT|RUN_EVAL|CANCEL_EXPERIMENT",
              "objective": "一句话目标描述（中文）",
              "experimentFamily": "实验族，如 MODULATION/CODING/CHANNEL/POLAR/BER 等；不明确时填 null",
              "modulation": "调制方式如 QPSK/BPSK/8PSK/OFDM/16QAM；未提及时填 null",
              "coding": "编码方式如 LDPC/BEC/极化码；未提及时填 null",
              "channel": "信道如 AWGN/多径/EPA/瑞利；未提及时填 null",
              "suppliedParameters": { "参数名": 值 },
              "requestedMetrics": ["BER", "SER", "EVM", "accuracy", ...],
              "requestedOutputs": [],
              "semanticTags": ["qpsk", "ber", "awgn", ...],
              "assumptions": [],
              "missingCriticalInformation": ["objective|modulation|channel|coding|parameters|metrics|...", ...],
              "confidence": 0.0 到 1.0
            }

            规则：
            1. intentType 判断意图，而不是关键词："看看能不能帮我跑一个 QPSK BER" = RUN_EXPERIMENT；
               "现在有什么模板？" = QUERY_TEMPLATES；"帮我跑个调制仿真" = RUN_EXPERIMENT。
            2. 用户没说 modulation/coding/channel 时这些字段必须是 null，绝不猜测。
            3. 一个实验请求缺少决定实验语义的关键信息（如"调制仿真"缺少 modulation/channel；
               "BER 实验"缺少具体调制与参数范围）时，把这些缺失项写入 missingCriticalInformation。
            4. suppliedParameters 只放用户明确给出的值（如 "0~10 dB" -> 不解析范围，只放能确定的）。
            5. 纯聊天/知识问题 = GENERAL_QA。
            """;

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectMapper objectMapper;
    private final MapOutputConverter outputConverter;

    public ExperimentIntentResolver(ObjectProvider<ChatModel> chatModels, ObjectMapper objectMapper) {
        this.chatModels = chatModels;
        this.objectMapper = objectMapper;
        this.outputConverter = new MapOutputConverter();
    }

    /** Resolve one user message in the context of the whole conversation. */
    public ExperimentIntent resolve(List<ConversationContextTurn> context, String message) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            return deterministicFallback(message);
        }
        try {
            StringBuilder conversation = new StringBuilder();
            if (context != null) {
                for (ConversationContextTurn turn : context) {
                    conversation.append(turn.role()).append(": ").append(turn.content()).append('\n');
                }
            }
            conversation.append("USER: ").append(message);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(RESOLVER_PROMPT),
                    new UserMessage(conversation.toString())));
            String output = model.call(prompt).getResult().getOutput().getText();
            // MapOutputConverter turns the model's JSON into a Map<String,Object> directly.
            Map<String, Object> map = outputConverter.convert(output);
            return fromMap(map);
        } catch (Exception e) {
            // The resolver must never crash the Agent; fall back to a conservative split.
            return deterministicFallback(message);
        }
    }

    private ExperimentIntent fromMap(Map<String, Object> map) {
        if (map == null) map = Map.of();
        return new ExperimentIntent(
                enumValue(IntentType.class, string(map, "intentType"), IntentType.GENERAL_QA),
                string(map, "objective"),
                nullableString(map, "experimentFamily"),
                nullableString(map, "modulation"),
                nullableString(map, "coding"),
                nullableString(map, "channel"),
                mapValue(map, "suppliedParameters"),
                stringList(map, "requestedMetrics"),
                stringList(map, "requestedOutputs"),
                stringList(map, "semanticTags"),
                stringList(map, "assumptions"),
                stringList(map, "missingCriticalInformation"),
                doubleValue(map, "confidence"));
    }

    /** Conservative offline split: query-ish sentences vs everything else. Only used with no model. */
    private ExperimentIntent deterministicFallback(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        boolean query = lower.contains("有什么") || lower.contains("有哪些")
                || lower.contains("怎么") || lower.contains("如何")
                || lower.contains("介绍") || lower.contains("解释")
                || lower.contains("详情") || lower.contains("查询")
                || lower.contains("什么模板") || lower.contains("看看");
        if (query) {
            return new ExperimentIntent(IntentType.QUERY_TEMPLATES, message, null, null, null,
                    null, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.5);
        }
        return new ExperimentIntent(IntentType.RUN_EXPERIMENT, message, null, null, null, null,
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.3);
    }

    private String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String nullableString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))
                ? null : String.valueOf(value);
    }

    private List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(String.valueOf(item));
            }
            return result;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            return List.of(String.valueOf(value));
        }
        return List.of();
    }

    private Map<String, Object> mapValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private double doubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        return 0.5;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Minimal view of a prior turn used as resolver context. */
    public record ConversationContextTurn(String role, String content) { }
}
