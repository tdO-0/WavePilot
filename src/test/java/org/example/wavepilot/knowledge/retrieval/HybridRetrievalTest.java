package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.DeterministicOfflineEmbeddingService;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.repository.InMemoryWavePilotKnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrievalTest {

    @Test
    void denseSparseAndRrfRanksAreKeptDistinctAndRrfRewardsCrossRetrieverHits() {
        DenseRetriever dense = (request, k) -> List.of(candidate("A", .9), candidate("B", .8));
        SparseRetriever sparse = new SparseRetriever() {
            @Override public void upsertDocument(List<KnowledgeChunk> chunks) { }
            @Override public List<RetrievalCandidate> search(KnowledgeSearchRequest request, int k) {
                return List.of(candidate("B", 12), candidate("C", 10));
            }
        };
        HybridRetrievalService service = service(dense, sparse, "noop");
        KnowledgeSearchRequest request = new KnowledgeSearchRequest("theory", 3,
                DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION);

        assertEquals(List.of("A", "B"), ids(service.search(request, RetrievalStrategy.DENSE_ONLY)));
        assertEquals(List.of("B", "C"), ids(service.search(request, RetrievalStrategy.BM25_ONLY)));
        assertEquals(List.of("B", "A", "C"), ids(service.search(request, RetrievalStrategy.HYBRID_RRF)));
    }

    @Test
    void denseAndBm25ApplyEquivalentMetadataFiltersAndHybridPreservesCitation() throws Exception {
        InMemoryWavePilotKnowledgeRepository denseStore = new InMemoryWavePilotKnowledgeRepository(
                new DeterministicOfflineEmbeddingService());
        try (LuceneBm25SparseRetriever sparse = new LuceneBm25SparseRetriever()) {
            KnowledgeChunk theory = chunk("THEORY", "DOC-T", DocumentType.THEORY,
                    "polar matrix exact query");
            KnowledgeChunk failure = chunk("FAILURE", "DOC-F", DocumentType.FAILURE_CASE,
                    "polar matrix exact query");
            denseStore.upsertDocument(List.of(theory));
            denseStore.upsertDocument(List.of(failure));
            sparse.upsertDocument(List.of(theory));
            sparse.upsertDocument(List.of(failure));
            DenseRetriever dense = new RepositoryDenseRetriever(denseStore);
            HybridRetrievalService service = service(dense, sparse, "noop");
            KnowledgeSearchRequest request = new KnowledgeSearchRequest("polar matrix exact query", 5,
                    DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION);

            assertEquals(List.of("THEORY"), ids(service.search(request, RetrievalStrategy.DENSE_ONLY)));
            assertEquals(List.of("THEORY"), ids(service.search(request, RetrievalStrategy.BM25_ONLY)));
            KnowledgeSearchResult result = service.search(request, RetrievalStrategy.HYBRID_RRF_RERANK)
                    .evidence().get(0);
            assertEquals("KB[DOC-T/THEORY]", result.citation());
            assertEquals("test://DOC-T", result.source());
            assertEquals("Theory", result.section());
        }
    }

    @Test
    void queryRouterClassifiesAllFourIntentsWithoutSeparateAgentPrompts() {
        QueryRouter router = new QueryRouter(new HybridRetrievalProperties());
        assertEquals(QueryType.THEORY, router.route(request("polar coding theorem")).queryType());
        assertEquals(QueryType.PARAMETER, router.route(request("sample parameter range")).queryType());
        assertEquals(QueryType.TROUBLESHOOTING, router.route(request("MATLAB error debug")).queryType());
        assertEquals(QueryType.EXPERIMENT_GUIDANCE, router.route(request("experiment workflow recipe")).queryType());
    }

    @Test
    void inferredTypeIsSoftRoutingButExplicitUserFilterRemainsHard() throws Exception {
        HybridRetrievalProperties properties = new HybridRetrievalProperties();
        QueryRouter router = new QueryRouter(properties);
        QueryRoute inferred = router.route(new KnowledgeSearchRequest("MATLAB error debug", 5,
                null, ExperimentType.POLAR_CODE_K_IDENTIFICATION));
        QueryRoute explicit = router.route(new KnowledgeSearchRequest("MATLAB error debug", 5,
                DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION));

        assertEquals(null, inferred.documentType());
        assertEquals(DocumentType.FAILURE_CASE, inferred.primaryDocumentType());
        assertTrue(inferred.fallbackDocumentTypes().contains(DocumentType.THEORY));
        assertEquals(DocumentType.THEORY, explicit.documentType());
        assertTrue(explicit.explicitDocumentFilter());
    }

    private HybridRetrievalService service(DenseRetriever dense, SparseRetriever sparse, String reranker) {
        HybridRetrievalProperties properties = new HybridRetrievalProperties();
        properties.setReranker(reranker);
        return new HybridRetrievalService(dense, sparse, new QueryRouter(properties), properties,
                List.of(new NoOpDocumentReranker(), new DeterministicDocumentReranker()));
    }

    private KnowledgeSearchRequest request(String query) {
        return new KnowledgeSearchRequest(query, 3, null,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION);
    }

    private List<String> ids(RetrievalResponse response) {
        return response.evidence().stream().map(KnowledgeSearchResult::chunkId).toList();
    }

    private RetrievalCandidate candidate(String id, double score) {
        return new RetrievalCandidate(KnowledgeSearchResult.from(
                chunk(id, "DOC-" + id, DocumentType.THEORY, id + " theory"), score), score);
    }

    private KnowledgeChunk chunk(String id, String documentId, DocumentType type, String content) {
        return new KnowledgeChunk(id, new KnowledgeDocumentMetadata(documentId, type,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, id + " title", "test://" + documentId,
                "v1", Instant.EPOCH), content, "Theory", Map.of("owner", "test"));
    }
}
