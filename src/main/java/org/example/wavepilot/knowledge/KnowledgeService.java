package org.example.wavepilot.knowledge;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class KnowledgeService {

    private final DocumentChunkService documentChunkService;
    private final KnowledgeIdFactory idFactory;
    private final WavePilotKnowledgeRepository repository;

    public KnowledgeService(DocumentChunkService documentChunkService, KnowledgeIdFactory idFactory,
                            WavePilotKnowledgeRepository repository) {
        this.documentChunkService = documentChunkService;
        this.idFactory = idFactory;
        this.repository = repository;
    }

    public KnowledgeIngestResult ingest(String content, DocumentType documentType,
                                        ExperimentType experimentType, String title,
                                        String source, String version) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Knowledge content is required");
        String documentId = idFactory.documentId(documentType, experimentType, title, source, version);
        KnowledgeDocumentMetadata metadata = new KnowledgeDocumentMetadata(documentId, documentType,
                experimentType, title, source, version, Instant.now());
        List<DocumentChunk> sourceChunks = documentChunkService.chunkDocument(content, source);
        List<KnowledgeChunk> chunks = sourceChunks.stream()
                .map(chunk -> new KnowledgeChunk(idFactory.chunkId(documentId, chunk.getChunkIndex()),
                        metadata, chunk.getContent()))
                .toList();
        if (chunks.isEmpty()) throw new IllegalArgumentException("Knowledge document produced no chunks");
        repository.upsertDocument(chunks);
        return new KnowledgeIngestResult(documentId, chunks.size(), documentType, experimentType,
                title, source, version, repository.storageDescription());
    }

    public List<KnowledgeSearchResult> search(KnowledgeSearchRequest request) {
        return repository.search(request);
    }

    public record KnowledgeIngestResult(
            String documentId,
            int chunkCount,
            DocumentType documentType,
            ExperimentType experimentType,
            String title,
            String source,
            String version,
            String storage) {
    }
}
