package org.example.wavepilot.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * One WavePilot embedding boundary backed by the Spring AI model configured for the application.
 */
@Service
public class SpringAiEmbeddingService implements WavePilotEmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public SpringAiEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModels) {
        this.embeddingModels = embeddingModels;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text is required");
        EmbeddingModel springAiModel = embeddingModels.getIfAvailable();
        if (springAiModel == null) {
            throw new IllegalStateException("Spring AI EmbeddingModel is unavailable; configure DashScope "
                    + "or set WAVEPILOT_EMBEDDING_OFFLINE=true with the in-memory repository");
        }
        return springAiModel.embed(text);
    }

    @Override
    public String providerDescription() {
        return embeddingModels.getIfAvailable() != null
                ? "Spring AI EmbeddingModel (DashScope)"
                : "Spring AI EmbeddingModel unavailable";
    }
}
