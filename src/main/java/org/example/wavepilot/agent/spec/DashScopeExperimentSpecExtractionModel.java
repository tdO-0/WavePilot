package org.example.wavepilot.agent.spec;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class DashScopeExperimentSpecExtractionModel implements ExperimentSpecExtractionModel {

    private final ObjectProvider<ChatModel> chatModels;

    public DashScopeExperimentSpecExtractionModel(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public String extract(String prompt) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("Spring AI ChatModel is unavailable; configure DashScope before parsing natural language");
        }
        return model.call(prompt);
    }
}
