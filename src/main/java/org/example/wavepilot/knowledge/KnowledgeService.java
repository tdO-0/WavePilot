package org.example.wavepilot.knowledge;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository;
import org.example.wavepilot.knowledge.retrieval.HybridRetrievalService;
import org.example.wavepilot.knowledge.retrieval.SparseRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class KnowledgeService {

    private final DocumentChunkService documentChunkService;
    private final KnowledgeIdFactory idFactory;
    private final WavePilotKnowledgeRepository repository;
    private final SparseRetriever sparseRetriever;
    private final HybridRetrievalService hybridRetrievalService;

    public KnowledgeService(DocumentChunkService documentChunkService, KnowledgeIdFactory idFactory,
                            WavePilotKnowledgeRepository repository) {
        this(documentChunkService, idFactory, repository, null, null);
    }

    @Autowired
    public KnowledgeService(DocumentChunkService documentChunkService, KnowledgeIdFactory idFactory,
                            WavePilotKnowledgeRepository repository,
                            SparseRetriever sparseRetriever,
                            HybridRetrievalService hybridRetrievalService) {
        this.documentChunkService = documentChunkService;
        this.idFactory = idFactory;
        this.repository = repository;
        this.sparseRetriever = sparseRetriever;
        this.hybridRetrievalService = hybridRetrievalService;
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
                        metadata, chunk.getContent(), chunk.getTitle(), java.util.Map.of()))
                .toList();
        if (chunks.isEmpty()) throw new IllegalArgumentException("Knowledge document produced no chunks");
        repository.upsertDocument(chunks);
        if (sparseRetriever != null) sparseRetriever.upsertDocument(chunks);
        return new KnowledgeIngestResult(documentId, chunks.size(), documentType, experimentType,
                title, source, version, repository.storageDescription());
    }

    public List<KnowledgeSearchResult> search(KnowledgeSearchRequest request) {
        return hybridRetrievalService == null
                ? repository.search(request)
                : hybridRetrievalService.search(request).evidence();
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
