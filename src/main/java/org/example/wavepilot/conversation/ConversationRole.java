package org.example.wavepilot.conversation;

/** Typed roles of one conversation turn; tool results are never disguised as user text. */
public enum ConversationRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM
}
