package org.example.wavepilot.knowledge.repository;

import org.example.wavepilot.knowledge.WavePilotEmbeddingService;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "wavepilot.knowledge.repository", havingValue = "memory")
public class InMemoryWavePilotKnowledgeRepository implements WavePilotKnowledgeRepository {

    private final WavePilotEmbeddingService embeddingService;
    private final ConcurrentMap<String, StoredChunk> chunks = new ConcurrentHashMap<>();

    public InMemoryWavePilotKnowledgeRepository(WavePilotEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public void upsertDocument(List<KnowledgeChunk> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) throw new IllegalArgumentException("Knowledge chunks are required");
        String documentId = newChunks.get(0).metadata().documentId();
        chunks.entrySet().removeIf(entry -> entry.getValue().chunk.metadata().documentId().equals(documentId));
        for (KnowledgeChunk chunk : newChunks) {
            chunks.put(chunk.chunkId(), new StoredChunk(chunk, embeddingService.embed(chunk.content())));
        }
    }

    @Override
    public List<KnowledgeSearchResult> search(KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("Knowledge query is required");
        }
        float[] query = embeddingService.embed(request.query());
        return chunks.values().stream()
                .filter(stored -> request.documentType() == null
                        || stored.chunk.metadata().documentType() == request.documentType())
                .filter(stored -> request.experimentType() == null
                        || stored.chunk.metadata().experimentType() == request.experimentType())
                .map(stored -> KnowledgeSearchResult.from(stored.chunk, cosine(query, stored.vector)))
                .sorted((left, right) -> Double.compare(right.similarityScore(), left.similarityScore()))
                .limit(request.normalizedTopK())
                .toList();
    }

    @Override
    public String storageDescription() { return "in-memory test/demo repository"; }

    private double cosine(float[] left, float[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("Embedding dimensions do not match");
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record StoredChunk(KnowledgeChunk chunk, float[] vector) { }
}
