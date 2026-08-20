package org.example.wavepilot.autonomous;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.GenericExperimentSpec;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.publish.TemplatePublishingService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The controlled autonomous loop: a model proposes one whitelisted tool call per round, the
 * executor runs it against production services, every round is recorded on the session
 * timeline, and human-only decision points (missing parameters, template approval) suspend
 * the loop until the user answers through the controller. The model can never approve,
 * publish or invent parameters.
 */
@Service
public class AutonomousSessionService {

    private static final int MAX_ROUNDS = 50;
    private static final int MAX_PARSE_FAILURES = 3;
    private static final int MAX_UNAUTHORIZED = 3;
    private static final int MAX_EXECUTION_FAILURES = 3;

    private final AutonomousToolExecutor executor;
    private final ExperimentDefinitionParser definitionParser;
    private final ExperimentDefinitionRegistry definitionRegistry;
    private final CandidateTemplateRepository candidateRepository;
    private final TemplatePublishingService publishing;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModels;
    private final AutonomousModel overrideModel;
    private final ConcurrentMap<String, AutonomousSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService loopExecutor = Executors.newCachedThreadPool();

    /** DashScope key from configuration; the not-configured placeholder means no real model. */
    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;

    @Autowired
    public AutonomousSessionService(AutonomousToolExecutor executor,
                                    ExperimentDefinitionParser definitionParser,
                                    ExperimentDefinitionRegistry definitionRegistry,
                                    CandidateTemplateRepository candidateRepository,
                                    TemplatePublishingService publishing,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<ChatModel> chatModels) {
        this(executor, definitionParser, definitionRegistry, candidateRepository, publishing,
                objectMapper, chatModels, null);
    }

    AutonomousSessionService(AutonomousToolExecutor executor,
                             ExperimentDefinitionParser definitionParser,
                             ExperimentDefinitionRegistry definitionRegistry,
                             CandidateTemplateRepository candidateRepository,
                             TemplatePublishingService publishing,
                             ObjectMapper objectMapper,
                             ObjectProvider<ChatModel> chatModels,
                             AutonomousModel overrideModel) {
        this.executor = executor;
        this.definitionParser = definitionParser;
        this.definitionRegistry = definitionRegistry;
        this.candidateRepository = candidateRepository;
        this.publishing = publishing;
        this.objectMapper = objectMapper;
        this.chatModels = chatModels;
        this.overrideModel = overrideModel;
    }

    public AutonomousSession start(String request) {
        return start(request, false);
    }

    public AutonomousSession start(String request, boolean analyzeResults) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("自主任务请求不能为空");
        }
        AutonomousModel model = resolveModel();
        AutonomousSession session = new AutonomousSession(request, model.name(), analyzeResults);
        sessions.put(session.sessionId(), session);
        // The user request is the first message of the model conversation; without it the
        // real model cannot reason about the task (and the loop has no task context).
        session.addChat(request);
        loopExecutor.submit(() -> runLoop(session, model));
        return session;
    }

    /** Start a goal loop from a semantically resolved intent (see WavePilotChatService). */
    public AutonomousSession start(org.example.wavepilot.intent.ExperimentIntent intent, String request) {
        AutonomousSession session = start(request, true);
        session.setExperimentIntent(intent);
        return session;
    }

    public AutonomousSession get(String sessionId) {
        AutonomousSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NoSuchElementException("自主会话不存在：" + sessionId);
        }
        return session;
    }

    public List<AutonomousSession> list() {
        return sessions.values().stream()
                .sorted(java.util.Comparator.comparing(AutonomousSession::createdAt).reversed())
                .toList();
    }

    /** User filled the parameter dialog; the values are baked into a pending spec and the loop resumes. */
    public AutonomousSession submitParams(String sessionId, Map<String, Object> params) {
        AutonomousSession session = require(sessionId);
        if (session.status() != AutonomousStatus.WAITING_PARAMS) {
            throw new IllegalStateException("会话不在等待参数状态：" + session.status());
        }
        // Natural-language parameter answer straight from the Agent chat ("0-10 dB, 100000
        // symbols"): do not try to parse it here; append it to the model conversation and let
        // the model re-run the parameter step with the user's answer in context.
        if (params != null && params.size() == 1 && params.containsKey("rawText")) {
            String raw = String.valueOf(params.get("rawText")).trim();
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("补参内容为空");
            }
            session.setPendingParams(Map.of());
            session.addChat("用户补充参数: " + raw);
            session.transition(AutonomousStatus.CHECKING_TEMPLATE);
            resume(session);
            return session;
        }
        Map<String, Object> pending = new LinkedHashMap<>(session.pendingParams());
        String specJson = buildSpecFromParams(pending, params);
        session.setPendingParams(Map.of());
        session.addChat("用户填写参数: " + safeJson(params) + (specJson == null ? "" : "\nspecJson: " + specJson));
        if (specJson != null) {
            session.setPendingParams(Map.of("specJson", specJson, "parameters", pending.get("parameters")));
        }
        session.transition(AutonomousStatus.CHECKING_TEMPLATE);
        resume(session);
        return session;
    }

    /** User answered the publish-approval dialog; only an explicit approver can continue. */
    public AutonomousSession submitApproval(String sessionId, boolean approved, String approvedBy) {
        AutonomousSession session = require(sessionId);
        if (session.status() != AutonomousStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("会话不在等待审批状态：" + session.status());
        }
        String candidateId = session.pendingCandidateId();
        if (candidateId == null) {
            throw new IllegalStateException("会话没有待审批的候选");
        }
        if (approved) {
            if (approvedBy == null || approvedBy.isBlank()) {
                throw new IllegalArgumentException("审批必须提供审批人标识");
            }
            TemplateCandidate active = publishing.approveAndPublish(candidateId, approvedBy);
            session.addChat("用户已批准发布候选 " + candidateId + "（" + active.templateId()
                    + " " + active.version() + "）");
            session.setPendingCandidateId(null);
            session.setPendingParams(Map.of());
            session.transition(AutonomousStatus.CHECKING_TEMPLATE);
            resume(session);
        } else {
            session.fail("用户拒绝了候选发布：" + candidateId);
            session.addStep("system", "用户拒绝发布候选 " + candidateId, null, null, AutonomousStatus.FAILED);
        }
        return session;
    }

    public AutonomousSession cancel(String sessionId) {
        AutonomousSession session = require(sessionId);
        if (session.status() == AutonomousStatus.SUCCEEDED
                || session.status() == AutonomousStatus.FAILED
                || session.status() == AutonomousStatus.CANCELLED) {
            return session;
        }
        session.transition(AutonomousStatus.CANCELLED);
        session.addStep("system", "用户取消了自主会话", null, null, AutonomousStatus.CANCELLED);
        return session;
    }

    private void runLoop(AutonomousSession session, AutonomousModel model) {
        int parseFailures = 0;
        int unauthorized = 0;
        int executionFailures = 0;
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (isStopped(session)) return;
                String output = model.respond(session.chatHistory());
                session.addStep("model", output, null, null, session.status());
                ToolCall call;
                try {
                    call = parseToolCall(output);
                    parseFailures = 0;
                } catch (Exception e) {
                    parseFailures++;
                    if (parseFailures >= MAX_PARSE_FAILURES) {
                        session.fail("模型连续 " + MAX_PARSE_FAILURES + " 轮未输出合法工具调用");
                        return;
                    }
                    session.addChat("（你的输出无法解析为工具调用 JSON："
                            + e.getMessage() + "。请只输出一个 JSON 工具调用，不要附加解释。）");
                    continue;
                }
                if (!AutonomousToolExecutor.WHITELIST.contains(call.tool())) {
                    unauthorized++;
                    if (unauthorized >= MAX_UNAUTHORIZED) {
                        session.fail("模型连续越权 " + MAX_UNAUTHORIZED + " 次，会话终止");
                        return;
                    }
                    session.addChat("工具 " + call.tool() + " 不在白名单中，已被拒绝。"
                            + "白名单：" + AutonomousToolExecutor.WHITELIST);
                    continue;
                }
                AutonomousToolExecutor.ToolOutcome outcome;
                try {
                    outcome = executor.execute(session, call.tool(), call.arguments());
                } catch (Exception e) {
                    // A failed tool call is fed back to the model so it can adjust its
                    // strategy (e.g. provide the missing template id); only repeated
                    // failures terminate the session.
                    executionFailures++;
                    if (executionFailures >= MAX_EXECUTION_FAILURES) {
                        session.fail("工具 " + call.tool() + " 连续失败 " + MAX_EXECUTION_FAILURES
                                + " 次：" + e.getMessage());
                        return;
                    }
                    session.addStep("tool", null, call.tool(),
                            "工具执行失败：" + e.getMessage(), session.status());
                    session.addChat("工具 " + call.tool() + " 执行失败：" + e.getMessage()
                            + "。请根据错误调整参数或策略后重试。");
                    continue;
                }
                session.addStep("tool", null, call.tool(), outcome.result(), session.status());
                session.addChat("工具结果(" + call.tool() + "): " + outcome.result());
                if (outcome.suspended()) return;
                if (outcome.finished()) {
                    session.transition(AutonomousStatus.SUCCEEDED);
                    session.addStep("system", "自主流程完成", null, null, AutonomousStatus.SUCCEEDED);
                    return;
                }
            }
            session.fail("自主流程超过最大轮数 " + MAX_ROUNDS);
        } catch (Exception e) {
            session.fail("自主流程异常：" + e.getMessage());
        }
    }

    private void resume(AutonomousSession session) {
        AutonomousModel model = resolveModel();
        loopExecutor.submit(() -> runLoop(session, model));
    }

    private boolean isStopped(AutonomousSession session) {
        AutonomousStatus status = session.status();
        return status == AutonomousStatus.CANCELLED || status == AutonomousStatus.FAILED
                || status == AutonomousStatus.SUCCEEDED || status == AutonomousStatus.BLOCKED
                || status == AutonomousStatus.WAITING_PARAMS || status == AutonomousStatus.WAITING_APPROVAL;
    }

    private AutonomousModel resolveModel() {
        if (overrideModel != null) return overrideModel;
        ChatModel chatModel = chatModels.getIfAvailable();
        boolean keyConfigured = dashScopeApiKey != null && !dashScopeApiKey.isBlank()
                && !"not-configured".equals(dashScopeApiKey);
        if (chatModel != null && keyConfigured) {
            return new AutonomousChatModel(chatModel, "qwen3.7-max");
        }
        // No usable model service (no ChatModel bean or no API key): fall back to the
        // scripted stub instead of real calls that would fail the whole session.
        return new AutonomousStubModel(false, "", List.of());
    }

    /** Builds the pending spec from the parameter dialog values. */
    private String buildSpecFromParams(Map<String, Object> pending, Map<String, Object> params) {
        ExperimentDefinition definition = resolveDefinition(pending);
        Map<String, Object> customParameters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null) customParameters.put(entry.getKey(), entry.getValue());
        }
        try {
            if (definition != null) {
                // Declarative template: a real generic spec (experimentTypeId + parameter
                // map), never a fake polar spec with codeLengths=[32].
                GenericExperimentSpec generic = new GenericExperimentSpec(
                        definition.experimentTypeId(), definition.templateId(), definition.version(),
                        customParameters, 20L, List.of("ACCURACY_CSV", "RUN_LOG"), "autonomous experiment");
                return objectMapper.writeValueAsString(generic);
            }
            // Built-in polar type without a template: the user's dialog values drive the
            // actual Spec fields (code lengths, error-rate sweep, sample count, trials).
            List<Integer> codeLengths = codeLengths(customParameters.remove("codeLengths"));
            double start = number(customParameters.remove("errorRateStart"), 0.0);
            double end = number(customParameters.remove("errorRateEnd"), 0.1);
            double step = number(customParameters.remove("errorRateStep"), 0.05);
            int samples = (int) number(customParameters.remove("sampleCount"), 20);
            int trials = (int) number(customParameters.remove("monteCarloTimes"), 10);
            ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                    codeLengths, start, end, step, samples, trials, 20L,
                    List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG),
                    "autonomous experiment",
                    null, customParameters);
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Integer> codeLengths(Object value) {
        if (value == null) return List.of(32);
        String text = String.valueOf(value);
        String[] parts = text.split(",");
        List<Integer> result = new ArrayList<>();
        for (String part : parts) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // malformed entries are dropped; the spec validator rejects an empty list
            }
        }
        return result.isEmpty() ? List.of(32) : result;
    }

    private double number(Object value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private ExperimentDefinition resolveDefinition(Map<String, Object> pending) {
        Object templateId = pending.get("templateId");
        if (templateId != null && !String.valueOf(templateId).isBlank()) {
            return definitionRegistry.byTemplateId(String.valueOf(templateId)).orElse(null);
        }
        Object candidateId = pending.get("candidateId");
        if (candidateId != null) {
            return candidateRepository.findById(String.valueOf(candidateId))
                    .map(candidate -> {
                        try {
                            return definitionParser.parse(candidate.definitionYaml());
                        } catch (Exception e) {
                            return null;
                        }
                    }).orElse(null);
        }
        return null;
    }

    private ToolCall parseToolCall(String output) throws Exception {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("输出中没有 JSON 对象");
        }
        var node = objectMapper.readTree(output.substring(start, end + 1));
        String tool = node.get("tool").asText();
        Map<String, Object> arguments = new LinkedHashMap<>();
        var args = node.get("arguments");
        if (args != null && args.isObject()) {
            args.fields().forEachRemaining(entry -> arguments.put(entry.getKey(),
                    objectMapper.convertValue(entry.getValue(), Object.class)));
        }
        return new ToolCall(tool, arguments);
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private AutonomousSession require(String sessionId) {
        AutonomousSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NoSuchElementException("自主会话不存在：" + sessionId);
        }
        return session;
    }

    private record ToolCall(String tool, Map<String, Object> arguments) { }
}
