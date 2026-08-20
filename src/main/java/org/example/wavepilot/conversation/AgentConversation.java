package org.example.wavepilot.conversation;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One continuous Agent conversation. The workbench and the chat controller share this
 * conversation across requests: a page refresh or a follow-up question continues the same
 * turn history instead of starting a brand-new agent. Context fields (active goal, resolved
 * intent, template, candidate, job, report) are carried here so the Agent never loses track
 * of what it is working on.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
public final class AgentConversation {

    private final String conversationId;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private final List<ConversationTurn> turns = new ArrayList<>();
    private volatile Map<String, Object> context = new LinkedHashMap<>();

    public AgentConversation() {
        this.conversationId = "CONV-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public AgentConversation(String conversationId) {
        this.conversationId = conversationId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public synchronized void addTurn(ConversationTurn turn) {
        turns.add(turn);
        updatedAt = Instant.now();
    }

    public synchronized void putContext(String key, Object value) {
        context = new LinkedHashMap<>(context);
        if (value == null) {
            context.remove(key);
        } else {
            context.put(key, value);
        }
    }

    public synchronized Object context(String key) {
        return context.get(key);
    }

    public String conversationId() { return conversationId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<ConversationTurn> turns() { return List.copyOf(turns); }
    public Map<String, Object> context() { return Map.copyOf(context); }
}
