package org.example.wavepilot.smoke;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.GetVersionResponse;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.example.wavepilot.knowledge.repository.WavePilotMilvusConstants;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.WavePilotEmbeddingService;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.repository.MilvusMetadataFilterBuilder;
import org.example.wavepilot.knowledge.repository.MilvusWavePilotKnowledgeRepository;
import org.example.wavepilot.knowledge.repository.WavePilotMilvusSchemaManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MilvusSmokeIT {

    private static final String SMOKE_COLLECTION = "wavepilot_knowledge_smoke_v1";
    private static final String THEORY_QUERY = "SMOKE_QUERY_THEORY";
    private static final String RECIPE_QUERY = "SMOKE_QUERY_RECIPE";

    private final boolean cleanupEnabled = Boolean.parseBoolean(
            System.getProperty("wavepilot.smoke.cleanup.enabled", "false"));
    private MilvusServiceClient client;
    private MilvusWavePilotKnowledgeRepository repository;
    private Fixed1024Embedding embedding;

    @BeforeAll
    void connectAndPrepareIsolatedCollection() {
        assertSafeSmokeCollection();
        String host = System.getProperty("wavepilot.smoke.milvus.host", "localhost");
        int port = Integer.parseInt(System.getProperty("wavepilot.smoke.milvus.port", "19530"));
        client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(host).withPort(port)
                .withConnectTimeout(10, TimeUnit.SECONDS).build());
        assertSuccess(client.checkHealth(), "check Milvus health");

        if (collectionExists()) {
            if (!cleanupEnabled) {
                fail(SMOKE_COLLECTION + " already exists; rerun with explicit "
                        + "-Dwavepilot.smoke.cleanup.enabled=true to reset/delete only the smoke collection");
            }
            dropSmokeCollection();
        }
        embedding = new Fixed1024Embedding();
        WavePilotMilvusSchemaManager schemaManager = new WavePilotMilvusSchemaManager(SMOKE_COLLECTION);
        repository = new MilvusWavePilotKnowledgeRepository(client, embedding, schemaManager,
                new MilvusMetadataFilterBuilder());
    }

    @AfterAll
    void closeAndOptionallyClean() throws InterruptedException {
        if (client == null) return;
        try {
            if (cleanupEnabled && collectionExists()) dropSmokeCollection();
        } finally {
            client.close(5);
        }
    }

    @Test
    void realMilvusSupportsWavePilotMetadataAndVectorSearch() throws IOException {
        ingestSamples();
        R<?> flushed = client.flush(FlushParam.newBuilder().addCollectionName(SMOKE_COLLECTION)
                .withSyncFlush(Boolean.TRUE).build());
        assertSuccess(flushed, "flush smoke collection");

        R<GetVersionResponse> version = client.getVersion();
        assertSuccess(version, "read Milvus version");
        assertVectorDimension();

        List<KnowledgeSearchResult> unfiltered = repository.search(request(THEORY_QUERY, null, null));
        assertEquals(5, unfiltered.size(), "unfiltered search must see all five smoke documents");

        List<KnowledgeSearchResult> theory = repository.search(request(
                THEORY_QUERY, DocumentType.THEORY, null));
        assertEquals(1, theory.size());
        assertEquals(DocumentType.THEORY, theory.get(0).documentType());
        assertFalse(theory.stream().anyMatch(result -> result.documentId().equals("KB-SMOKE-UNRELATED")),
                "higher-similarity unrelated content must not bypass the THEORY filter");

        List<KnowledgeSearchResult> recipes = repository.search(request(
                RECIPE_QUERY, DocumentType.EXPERIMENT_RECIPE, null));
        assertEquals(1, recipes.size());
        assertEquals("KB-SMOKE-RECIPE", recipes.get(0).documentId());

        List<KnowledgeSearchResult> polar = repository.search(request(
                THEORY_QUERY, null, ExperimentType.POLAR_CODE_K_IDENTIFICATION));
        assertEquals(5, polar.size());

        List<KnowledgeSearchResult> combined = repository.search(request(
                THEORY_QUERY, DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION));
        assertEquals(1, combined.size());
        KnowledgeSearchResult result = combined.get(0);
        assertEquals("KB-SMOKE-THEORY-CH-0000", result.chunkId());
        assertEquals("KB-SMOKE-THEORY", result.documentId());
        assertEquals("极化码码维数识别原理", result.title());
        assertEquals("sample://polar-theory", result.source());
        assertEquals("KB[KB-SMOKE-THEORY/KB-SMOKE-THEORY-CH-0000]", result.citation());

        float rawDistance = rawTheoryDistance();
        assertEquals(1.0d / (1.0d + Math.max(0.0f, rawDistance)), result.similarityScore(), 1.0e-6,
                "similarityScore is only the display transform 1/(1+L2 distance)");
        assertTrue(result.similarityScore() >= 0 && result.similarityScore() <= 1,
                "bounded display value is not asserted as a probability or confidence");

        System.out.printf("MILVUS_SMOKE version=%s collection=%s vectors=%d documents=%d cleanup=%s%n",
                version.getData().getVersion(), SMOKE_COLLECTION, WavePilotMilvusConstants.VECTOR_DIMENSION,
                unfiltered.size(), cleanupEnabled);
    }

    private void ingestSamples() throws IOException {
        repository.upsertDocument(List.of(chunk("KB-SMOKE-THEORY", DocumentType.THEORY,
                "极化码码维数识别原理", "sample://polar-theory",
                readSample("polar-k-identification-theory.md"))));
        repository.upsertDocument(List.of(chunk("KB-SMOKE-STANDARD", DocumentType.STANDARD,
                "蒙特卡洛参数规范", "sample://monte-carlo-standard",
                readSample("monte-carlo-parameters.md"))));
        repository.upsertDocument(List.of(chunk("KB-SMOKE-RECIPE", DocumentType.EXPERIMENT_RECIPE,
                "极化码实验配方", "sample://polar-recipe",
                readSample("polar-experiment-recipe.md"))));
        repository.upsertDocument(List.of(chunk("KB-SMOKE-MATLAB", DocumentType.MATLAB_GUIDE,
                "MATLAB 常见错误", "sample://matlab-errors",
                readSample("matlab-common-errors.md"))));
        repository.upsertDocument(List.of(chunk("KB-SMOKE-UNRELATED", DocumentType.FAILURE_CASE,
                "办公室访客制度", "sample://unrelated-policy",
                "UNRELATED_HIGH_SIMILARITY\n" + readSample("unrelated-office-policy.md"))));
    }

    private KnowledgeChunk chunk(String documentId, DocumentType documentType, String title,
                                 String source, String content) {
        String chunkId = documentId + "-CH-0000";
        return new KnowledgeChunk(chunkId, new KnowledgeDocumentMetadata(documentId, documentType,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, title, source, "smoke-v1", Instant.now()), content);
    }

    private String readSample(String name) throws IOException {
        return Files.readString(Path.of("src", "docs", "knowledge-samples", name));
    }

    private KnowledgeSearchRequest request(String query, DocumentType documentType,
                                           ExperimentType experimentType) {
        return new KnowledgeSearchRequest(query, 10, documentType, experimentType);
    }

    private void assertVectorDimension() {
        R<DescribeCollectionResponse> described = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(SMOKE_COLLECTION).build());
        assertSuccess(described, "describe smoke collection");
        var vectorField = new DescCollResponseWrapper(described.getData()).getFields().stream()
                .filter(field -> WavePilotMilvusSchemaManager.VECTOR_FIELD.equals(field.getName()))
                .findFirst().orElseThrow();
        assertEquals(WavePilotMilvusConstants.VECTOR_DIMENSION, vectorField.getDimension());
        assertEquals(WavePilotMilvusConstants.VECTOR_DIMENSION, embedding.embed(THEORY_QUERY).length);
    }

    private float rawTheoryDistance() {
        List<Float> queryVector = toList(embedding.embed(THEORY_QUERY));
        R<SearchResults> searched = client.search(SearchParam.newBuilder()
                .withCollectionName(SMOKE_COLLECTION)
                .withVectorFieldName(WavePilotMilvusSchemaManager.VECTOR_FIELD)
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(1).withMetricType(MetricType.L2)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr("documentType == \"THEORY\" && experimentType == \"POLAR_CODE_K_IDENTIFICATION\"")
                .withOutFields(List.of("chunkId")).withParams("{\"nprobe\":10}").build());
        assertSuccess(searched, "read raw L2 distance");
        return new SearchResultsWrapper(searched.getData().getResults()).getIDScore(0).get(0).getScore();
    }

    private List<Float> toList(float[] vector) {
        List<Float> values = new java.util.ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        return values;
    }

    private boolean collectionExists() {
        R<Boolean> result = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(SMOKE_COLLECTION).build());
        assertSuccess(result, "check smoke collection");
        return Boolean.TRUE.equals(result.getData());
    }

    private void dropSmokeCollection() {
        assertSafeSmokeCollection();
        R<RpcStatus> dropped = client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(SMOKE_COLLECTION).build());
        assertSuccess(dropped, "drop explicitly enabled smoke collection");
    }

    private void assertSafeSmokeCollection() {
        assertEquals("wavepilot_knowledge_smoke_v1", SMOKE_COLLECTION);
        assertFalse("biz".equals(SMOKE_COLLECTION));
        assertFalse(WavePilotMilvusSchemaManager.PRODUCTION_COLLECTION_NAME.equals(SMOKE_COLLECTION));
    }

    private void assertSuccess(R<?> response, String operation) {
        assertNotNull(response, operation + " returned no response");
        assertEquals(0, response.getStatus(),
                () -> operation + " failed with Milvus status " + response.getStatus());
    }

    private static final class Fixed1024Embedding implements WavePilotEmbeddingService {
        @Override
        public float[] embed(String text) {
            float[] vector = new float[WavePilotMilvusConstants.VECTOR_DIMENSION];
            if (THEORY_QUERY.equals(text) || text.contains("UNRELATED_HIGH_SIMILARITY")) {
                vector[0] = 1.0f;
            } else if (text.contains("极化码码维数识别：基础原理")) {
                vector[0] = 0.9f;
                vector[1] = 0.1f;
            } else if (RECIPE_QUERY.equals(text) || text.contains("极化码码维数识别实验配方")) {
                vector[1] = 1.0f;
            } else if (text.contains("Monte Carlo")) {
                vector[0] = 0.4f;
                vector[1] = 0.6f;
            } else if (text.contains("MATLAB")) {
                vector[0] = 0.2f;
                vector[1] = 0.8f;
            } else {
                vector[0] = 0.5f;
                vector[1] = 0.5f;
            }
            return vector;
        }

        @Override
        public String providerDescription() {
            return "Phase 3.5 deterministic 1024-dimensional smoke vectors";
        }
    }
}
