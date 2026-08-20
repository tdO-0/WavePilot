package org.example.wavepilot.conversation;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory conversation registry; a conversation continues until the project stops. */
@Component
public class ConversationStore {

    private final ConcurrentMap<String, AgentConversation> conversations = new ConcurrentHashMap<>();

    public AgentConversation getOrCreate(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversations.computeIfAbsent(conversationId, AgentConversation::new);
        }
        AgentConversation conversation = new AgentConversation();
        conversations.put(conversation.conversationId(), conversation);
        return conversation;
    }

    public AgentConversation get(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId))
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + conversationId));
    }

    public List<AgentConversation> list() {
        return conversations.values().stream()
                .sorted(Comparator.comparing(AgentConversation::updatedAt).reversed())
                .toList();
    }
}
