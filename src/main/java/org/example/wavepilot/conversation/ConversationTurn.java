package org.example.wavepilot.conversation;

import java.time.Instant;

/**
 * One typed turn of an Agent conversation. {@code content} carries the human-readable text,
 * {@code toolName}/{@code toolResult} carry structured tool feedback when the role is TOOL.
 */
public record ConversationTurn(
        ConversationRole role,
        String content,
        String toolName,
        String toolResult,
        Instant timestamp) {

    public ConversationTurn {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static ConversationTurn user(String content) {
        return new ConversationTurn(ConversationRole.USER, content, null, null, Instant.now());
    }

    public static ConversationTurn assistant(String content) {
        return new ConversationTurn(ConversationRole.ASSISTANT, content, null, null, Instant.now());
    }

    public static ConversationTurn tool(String toolName, String toolResult) {
        return new ConversationTurn(ConversationRole.TOOL, null, toolName, toolResult, Instant.now());
    }

    public static ConversationTurn system(String content) {
        return new ConversationTurn(ConversationRole.SYSTEM, content, null, null, Instant.now());
    }
}
