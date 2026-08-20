package org.example.wavepilot.knowledge;

public interface WavePilotEmbeddingService {
    float[] embed(String text);
    String providerDescription();
}
