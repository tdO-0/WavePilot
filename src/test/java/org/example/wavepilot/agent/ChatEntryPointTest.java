package org.example.wavepilot.agent;

import org.example.wavepilot.autonomous.AutonomousSession;
import org.example.wavepilot.autonomous.AutonomousSessionService;
import org.example.wavepilot.conversation.AgentConversation;
import org.example.wavepilot.conversation.ConversationStore;
import org.example.wavepilot.conversation.ConversationTurn;
import org.example.wavepilot.intent.ExperimentIntent;
import org.example.wavepilot.intent.ExperimentIntentResolver;
import org.example.wavepilot.intent.IntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single Agent chat entry point is intent-driven and conversation-continuous:
 * experiment intents route to the controlled goal loop, clarification-requiring intents
 * answer with a question (never a fabricated run), and the same conversationId keeps the
 * same conversation across requests.
 */
@ExtendWith(MockitoExtension.class)
class ChatEntryPointTest {

    @Mock WavePilotAgentTools agentTools;
    @Mock AutonomousSessionService autonomousSessions;
    @Mock ExperimentIntentResolver intentResolver;

    private ConversationStore store;

    private WavePilotChatService service() {
        store = new ConversationStore();
        return new WavePilotChatService(emptyChatModels(), agentTools, autonomousSessions,
                "mock", intentResolver, store);
    }

    private org.springframework.beans.factory.ObjectProvider<ChatModel> emptyChatModels() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public ChatModel getObject() { return null; }
            @Override public ChatModel getObject(Object... args) { return null; }
            @Override public ChatModel getIfAvailable() { return null; }
            @Override public ChatModel getIfUnique() { return null; }
            @Override public ChatModel getIfAvailable(
                    java.util.function.Supplier<ChatModel> defaultSupplier) { return null; }
            @Override public ChatModel getIfUnique(
                    java.util.function.Supplier<ChatModel> defaultSupplier) { return null; }
            @Override public void forEach(java.util.function.Consumer<? super ChatModel> action) { }
            @Override public Stream<ChatModel> stream() { return Stream.empty(); }
        };
    }

    @BeforeEach
    void stubResolverDefaults() {
        // The resolver is mocked at the service boundary; semantic cases are covered by
        // ExperimentIntentResolver tests separately.
        when(intentResolver.resolve(anyList(), anyString())).thenReturn(new ExperimentIntent(
                IntentType.GENERAL_QA, "", null, null, null, null, Map.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), 0.5));
    }

    @Test
    void runExperimentIntentRoutesToTheControlledGoalLoop() {
        when(intentResolver.resolve(anyList(), anyString())).thenReturn(intent(
                IntentType.RUN_EXPERIMENT, List.of()));
        AutonomousSession session = new AutonomousSession("跑一个 QPSK BER 实验", "stub");
        when(autonomousSessions.start(any(ExperimentIntent.class), anyString())).thenReturn(session);

        WavePilotChatService.ChatResponse response = service().chat(null, "跑一个 QPSK BER 实验");

        verify(autonomousSessions).start(any(ExperimentIntent.class), anyString());
        assertEquals(session.sessionId(), response.goalSessionId(),
                "experiment intents must carry the goal session id");
        assertNotNull(response.conversationId(), "responses must carry the conversation id");
    }

    @Test
    void clarificationRequiredIntentsAnswerWithAQuestionAndDoNotStartAnything() {
        when(intentResolver.resolve(anyList(), anyString())).thenReturn(intent(
                IntentType.RUN_EXPERIMENT, List.of("modulation", "channel")));

        WavePilotChatService.ChatResponse response = service().chat(null, "帮我跑个调制仿真");

        assertNull(response.goalSessionId(), "no goal may start before clarification");
        assertEquals("WAITING_CLARIFICATION", response.goalStatus());
        assertTrue(response.answer().contains("调制方式"),
                "the agent must ask which modulation, got: " + response.answer());
    }

    @Test
    void queryTemplatesStaysOnTheQAPathAndFailsWithoutAModel() {
        when(intentResolver.resolve(anyList(), anyString())).thenReturn(intent(
                IntentType.QUERY_TEMPLATES, List.of()));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service().chat(null, "目前系统里有哪些可用模板？"));
        assertTrue(error.getMessage().contains("ChatModel is unavailable"));
    }

    @Test
    void sameConversationIdContinuesTheSameConversation() {
        when(intentResolver.resolve(anyList(), anyString())).thenReturn(intent(
                IntentType.RUN_EXPERIMENT, List.of()));
        AutonomousSession session = new AutonomousSession("跑一个 QPSK BER 实验", "stub");
        when(autonomousSessions.start(any(ExperimentIntent.class), anyString())).thenReturn(session);
        WavePilotChatService service = service();

        // First request creates the conversation and returns its id.
        WavePilotChatService.ChatResponse first = service.chat(null, "跑一个 QPSK BER 实验");
        assertNotNull(first.conversationId());

        // Second request with the returned id must continue the same conversation.
        WavePilotChatService.ChatResponse second =
                service.chat(first.conversationId(), "再跑一次");
        assertEquals(first.conversationId(), second.conversationId());
        AgentConversation conversation = store.get(first.conversationId());
        assertEquals(4, conversation.turns().size(),
                "two user turns + two assistant turns must accumulate in one conversation");
        assertEquals("user", conversation.turns().get(0).role().name().toLowerCase());
    }

    private ExperimentIntent intent(IntentType type, List<String> missing) {
        return new ExperimentIntent(type, "objective", null, null, null, null, Map.of(),
                List.of(), List.of(), List.of(), List.of(), missing, 0.9);
    }
}
