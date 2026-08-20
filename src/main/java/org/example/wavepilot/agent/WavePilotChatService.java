package org.example.wavepilot.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.wavepilot.autonomous.AutonomousSession;
import org.example.wavepilot.autonomous.AutonomousSessionService;
import org.example.wavepilot.conversation.AgentConversation;
import org.example.wavepilot.conversation.ConversationStore;
import org.example.wavepilot.conversation.ConversationTurn;
import org.example.wavepilot.intent.ExperimentIntent;
import org.example.wavepilot.intent.ExperimentIntentResolver;
import org.example.wavepilot.intent.IntentType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Single chat entry point behind the Agent workbench. The conversation is a first-class
 * object: the same {@link AgentConversation} (identified by conversationId) continues across
 * requests, so the workbench is a real conversation, not a fresh agent per message.
 *
 * <p>Routing is semantic: {@link ExperimentIntentResolver} classifies the message
 * (GENERAL_QA / QUERY_TEMPLATES / RUN_EXPERIMENT / CREATE_TEMPLATE / …). Experiment intents
 * run through the controlled goal loop; queries and general questions stay on the Q&amp;A path
 * whose ReactAgent can still call read-only tools.
 */
@Service
public class WavePilotChatService {

    private final ObjectProvider<ChatModel> chatModels;
    private final WavePilotAgentTools agentTools;
    private final String runnerType;
    private final AutonomousSessionService autonomousSessions;
    private final ExperimentIntentResolver intentResolver;
    private final ConversationStore conversationStore;

    public WavePilotChatService(ObjectProvider<ChatModel> chatModels, WavePilotAgentTools agentTools,
                                AutonomousSessionService autonomousSessions,
                                @Value("${wavepilot.runner.type:mock}") String runnerType,
                                ExperimentIntentResolver intentResolver,
                                ConversationStore conversationStore) {
        this.chatModels = chatModels;
        this.agentTools = agentTools;
        this.autonomousSessions = autonomousSessions;
        this.runnerType = runnerType;
        this.intentResolver = intentResolver;
        this.conversationStore = conversationStore;
    }

    public ChatResponse chat(String conversationId, String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Chat message is required");
        AgentConversation conversation = conversationStore.getOrCreate(conversationId);
        conversation.addTurn(ConversationTurn.user(message));

        List<ExperimentIntentResolver.ConversationContextTurn> context =
                conversation.turns().stream()
                        .map(turn -> new ExperimentIntentResolver.ConversationContextTurn(
                                turn.role().name(), turn.content() == null ? "" : turn.content()))
                        .toList();
        ExperimentIntent intent = intentResolver.resolve(context, message);
        conversation.putContext("experimentIntent", intent);

        if (intent.intentType() == IntentType.RUN_EXPERIMENT
                || intent.intentType() == IntentType.CREATE_TEMPLATE
                || intent.intentType() == IntentType.ANALYZE_RESULT
                || intent.intentType() == IntentType.REPLAY_EXPERIMENT
                || intent.intentType() == IntentType.RUN_EVAL
                || intent.intentType() == IntentType.CANCEL_EXPERIMENT) {
            return startGoal(conversation, intent, message);
        }
        return answerConversationally(conversation, intent, message);
    }

    private ChatResponse startGoal(AgentConversation conversation, ExperimentIntent intent, String message) {
        if (intent.needsClarification()) {
            String question = clarificationQuestion(intent);
            conversation.addTurn(ConversationTurn.assistant(question));
            return new ChatResponse(conversation.conversationId(), question,
                    "mock".equalsIgnoreCase(runnerType), null, "WAITING_CLARIFICATION");
        }
        // SAFE AUTO EXECUTION: the goal loop advances safe steps (template resolution,
        // candidate generation/validation/smoke, spec, job, report) automatically and only
        // parks at human gates (approval, parameters).
        AutonomousSession session = autonomousSessions.start(intent, message);
        conversation.putContext("activeGoalId", session.sessionId());
        String answer = "已开始执行你的目标（会话 " + session.sessionId() + "）。"
                + "Agent 会自动推进安全步骤；只有在模板发布或需要补充参数时才会请你介入。";
        conversation.addTurn(ConversationTurn.assistant(answer));
        return new ChatResponse(conversation.conversationId(), answer,
                "mock".equalsIgnoreCase(runnerType), session.sessionId(), session.status().name());
    }

    private ChatResponse answerConversationally(AgentConversation conversation,
                                                ExperimentIntent intent, String message) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) throw new IllegalStateException("Spring AI ChatModel is unavailable; configure DashScope");
        ReactAgent agent = ReactAgent.builder()
                .name("wavepilot_communication_experiment_agent")
                .model(model)
                .systemPrompt(WavePilotAgentPrompt.SYSTEM_PROMPT)
                .methodTools(agentTools)
                .build();
        try {
            String answer = agent.call(message).getText();
            boolean mock = "mock".equalsIgnoreCase(runnerType);
            if (mock && !containsMockDisclosure(answer)) {
                answer = "【Mock Runner】当前实验任务和产物均为模拟数据，不是真实 MATLAB 仿真。\n" + answer;
            }
            conversation.addTurn(ConversationTurn.assistant(answer));
            return new ChatResponse(conversation.conversationId(), answer, mock, null, null);
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("WavePilot ReactAgent execution failed", e);
        }
    }

    /** Natural clarification question derived from what the intent is missing. */
    static String clarificationQuestion(ExperimentIntent intent) {
        List<String> missing = intent.missingCriticalInformation();
        if (missing.contains("modulation") && missing.contains("channel")) {
            return "请先明确实验语义：你希望用哪种调制方式（如 QPSK / BPSK / 8PSK / OFDM）？"
                    + "以及哪种信道模型（如 AWGN / 多径 EPA）？另外你主要观察 BER、SER 还是星座图？";
        }
        if (missing.contains("modulation")) {
            return "你希望用哪种调制方式（如 QPSK / BPSK / 8PSK / OFDM）？以及主要观察 BER、SER 还是星座图？";
        }
        if (missing.contains("channel")) {
            return "你希望用哪种信道模型（如 AWGN / 多径 EPA）？";
        }
        if (missing.contains("parameters")) {
            return "实验参数还不完整：请给出关键参数范围（如 Eb/N0 或信噪比范围、符号数等）。";
        }
        if (missing.contains("objective")) {
            return "请说明这个实验想观察什么（如 BER 随信噪比变化、CP 长度影响、码率对比等）。";
        }
        return "请补充：" + String.join("、", missing) + "。";
    }

    private boolean containsMockDisclosure(String answer) {
        if (answer == null) return false;
        String lower = answer.toLowerCase();
        return lower.contains("mock") || answer.contains("模拟数据") || answer.contains("模拟结果");
    }

    public record ChatResponse(String conversationId, String answer, boolean mockRunner,
                               String goalSessionId, String goalStatus) { }
}
