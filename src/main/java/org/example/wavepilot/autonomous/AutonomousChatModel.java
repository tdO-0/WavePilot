package org.example.wavepilot.autonomous;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-model adapter for the autonomous loop; only used when a ChatModel is available.
 *
 * <p>The loop history is a list of typed turns ({@link org.example.wavepilot.conversation.ConversationTurn}
 * by contract). This adapter maps roles explicitly: USER → UserMessage, ASSISTANT →
 * AssistantMessage, SYSTEM → SystemMessage, TOOL → a tool-result message that is
 * <em>never</em> disguised as a user message. The legacy string history (used by the stub
 * and offline tests) is interpreted by its prefixes: "工具结果(…)" lines are tool feedback,
 * "用户…" lines are user turns, everything else stays a user turn for backward
 * compatibility with the scripted flow.
 */
public class AutonomousChatModel implements AutonomousModel {

    private final ChatModel chatModel;
    private final String name;

    public AutonomousChatModel(ChatModel chatModel, String name) {
        this.chatModel = chatModel;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String respond(List<String> chatHistory) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AutonomousAgentPrompt.SYSTEM_PROMPT));
        boolean first = true;
        for (String turn : chatHistory) {
            if (turn == null) continue;
            String text = turn.trim();
            if (text.isEmpty()) continue;
            if (isToolResult(text)) {
                // Tool feedback: a dedicated message, never a user turn.
                messages.add(new SystemMessage(text));
            } else if (text.startsWith("用户") || first) {
                messages.add(new UserMessage(text));
            } else {
                // The model's own previous tool-call JSON proposal.
                messages.add(new AssistantMessage(text));
            }
            first = false;
        }
        return chatModel.call(new Prompt(messages)).getResult().getOutput().getText();
    }

    private boolean isToolResult(String text) {
        return text.startsWith("工具结果(") || text.startsWith("工具 ") || text.startsWith("（你的输出");
    }
}
