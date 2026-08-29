package org.example.wavepilot.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Opt-in DashScope listwise scorer; Java owns candidate identity and validates its output. */
@Component
@ConditionalOnProperty(name = "wavepilot.knowledge.hybrid.model-reranker-enabled", havingValue = "true")
public class DashScopeListwiseRerankModel implements ModelBasedDocumentReranker.ScoringModel {
    private static final int MAX_CONTENT = 500;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DashScopeListwiseRerankModel(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> rankChunkIds(String query, List<RetrievalCandidate> candidates) {
        StringBuilder input = new StringBuilder("query: ").append(query).append("\ncandidates:\n");
        for (RetrievalCandidate candidate : candidates) {
            var evidence = candidate.evidence();
            String content = evidence.content().length() <= MAX_CONTENT
                    ? evidence.content() : evidence.content().substring(0, MAX_CONTENT);
            input.append("- chunkId: ").append(evidence.chunkId())
                    .append("\n  title: ").append(evidence.title())
                    .append("\n  section: ").append(evidence.section())
                    .append("\n  content: ").append(content.replace('\n', ' ')).append('\n');
        }
        String output = chatModel.call(new Prompt(List.of(
                new SystemMessage("""
                        Rank only the supplied chunks by relevance to the query. Return strict JSON:
                        {"chunkIds":["id1","id2"]}. Include every supplied chunkId exactly once.
                        Never create, rename, omit or duplicate an id. Do not include prose.
                        """),
                new UserMessage(input.toString())))).getResult().getOutput().getText();
        try {
            int start = output.indexOf('{');
            int end = output.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON object");
            JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
            JsonNode ids = root.get("chunkIds");
            if (!root.isObject() || root.size() != 1 || ids == null || !ids.isArray()) {
                throw new IllegalArgumentException("only chunkIds array is allowed");
            }
            List<String> order = new ArrayList<>();
            ids.forEach(node -> {
                if (!node.isTextual()) throw new IllegalArgumentException("chunkId must be a string");
                order.add(node.textValue());
            });
            return List.copyOf(order);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid listwise reranker output", e);
        }
    }
}
