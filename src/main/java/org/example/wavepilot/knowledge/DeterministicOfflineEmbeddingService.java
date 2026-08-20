package org.example.wavepilot.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit offline embedding boundary: activated only by
 * {@code wavepilot.embedding.offline=true}. It produces deterministic hash-based vectors so
 * the in-memory knowledge repository works without DashScope. When this service is active it
 * becomes the primary WavePilotEmbeddingService; the DashScope-backed service stays defined
 * but unused. It is a software-loop test/demo embedding, never a semantic embedding.
 */
@Service
@Primary
@ConditionalOnProperty(name = "wavepilot.embedding.offline", havingValue = "true", matchIfMissing = false)
public class DeterministicOfflineEmbeddingService implements WavePilotEmbeddingService {

    private static final Pattern TERM = Pattern.compile("[\\p{IsHan}]|[A-Za-z0-9_]+");
    private static final int DIMENSIONS = 64;

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text is required");
        }
        float[] vector = new float[DIMENSIONS];
        for (String term : terms(text)) {
            Random random = new Random(term.hashCode());
            for (int index = 0; index < DIMENSIONS; index++) {
                vector[index] += random.nextFloat() * 2 - 1;
            }
        }
        double norm = 0;
        for (float value : vector) norm += (double) value * value;
        norm = Math.sqrt(norm);
        if (norm == 0) return vector;
        for (int index = 0; index < DIMENSIONS; index++) {
            vector[index] = (float) (vector[index] / norm);
        }
        return vector;
    }

    @Override
    public String providerDescription() {
        return "deterministic offline embedding (wavepilot.embedding.offline=true); "
                + "no DashScope call is made";
    }

    private List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TERM.matcher(text);
        while (matcher.find()) terms.add(matcher.group());
        return terms;
    }
}
