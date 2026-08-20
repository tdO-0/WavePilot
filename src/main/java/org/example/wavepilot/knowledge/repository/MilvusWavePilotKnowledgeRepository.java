package org.example.wavepilot.knowledge.repository;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.WavePilotEmbeddingService;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "wavepilot.knowledge.repository", havingValue = "milvus", matchIfMissing = true)
public class MilvusWavePilotKnowledgeRepository implements WavePilotKnowledgeRepository {

    private final MilvusServiceClient client;
    private final WavePilotEmbeddingService embeddingService;
    private final WavePilotMilvusSchemaManager schemaManager;
    private final MilvusMetadataFilterBuilder filterBuilder;
    private volatile boolean initialized;

    public MilvusWavePilotKnowledgeRepository(@Lazy MilvusServiceClient client,
                                              WavePilotEmbeddingService embeddingService,
                                              WavePilotMilvusSchemaManager schemaManager,
                                              MilvusMetadataFilterBuilder filterBuilder) {
        this.client = client;
        this.embeddingService = embeddingService;
        this.schemaManager = schemaManager;
        this.filterBuilder = filterBuilder;
    }

    @Override
    public void upsertDocument(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("Knowledge chunks are required");
        initialize();
        String documentId = chunks.get(0).metadata().documentId();
        R<MutationResult> deleted = client.delete(DeleteParam.newBuilder()
                .withCollectionName(schemaManager.collectionName())
                .withExpr("documentId == \"" + documentId + "\"").build());
        if (deleted.getStatus() != 0) throw new IllegalStateException("Could not replace document: " + deleted.getMessage());

        List<String> chunkIds = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();
        List<String> documentTypes = new ArrayList<>();
        List<String> experimentTypes = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        List<String> createdTimes = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            if (!documentId.equals(chunk.metadata().documentId())) {
                throw new IllegalArgumentException("All chunks in one upsert must share documentId");
            }
            float[] vector = embeddingService.embed(chunk.content());
            if (vector.length != WavePilotMilvusConstants.VECTOR_DIMENSION) {
                throw new IllegalStateException("Embedding dimension must be "
                        + WavePilotMilvusConstants.VECTOR_DIMENSION
                        + " but was " + vector.length);
            }
            chunkIds.add(chunk.chunkId());
            documentIds.add(documentId);
            documentTypes.add(chunk.metadata().documentType().name());
            experimentTypes.add(chunk.metadata().experimentType().name());
            titles.add(chunk.metadata().title());
            sources.add(chunk.metadata().source());
            versions.add(chunk.metadata().version());
            createdTimes.add(chunk.metadata().createdAt().toString());
            contents.add(chunk.content());
            vectors.add(toList(vector));
        }
        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("chunkId", chunkIds),
                new InsertParam.Field("documentId", documentIds),
                new InsertParam.Field("documentType", documentTypes),
                new InsertParam.Field("experimentType", experimentTypes),
                new InsertParam.Field("title", titles),
                new InsertParam.Field("source", sources),
                new InsertParam.Field("version", versions),
                new InsertParam.Field("createdAt", createdTimes),
                new InsertParam.Field("content", contents),
                new InsertParam.Field(WavePilotMilvusSchemaManager.VECTOR_FIELD, vectors));
        R<MutationResult> inserted = client.insert(InsertParam.newBuilder()
                .withCollectionName(schemaManager.collectionName())
                .withFields(fields).build());
        if (inserted.getStatus() != 0) throw new IllegalStateException("Could not index document: " + inserted.getMessage());
    }

    @Override
    public List<KnowledgeSearchResult> search(KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("Knowledge query is required");
        }
        initialize();
        float[] embedded = embeddingService.embed(request.query());
        SearchParam.Builder search = SearchParam.newBuilder()
                .withCollectionName(schemaManager.collectionName())
                .withVectorFieldName(WavePilotMilvusSchemaManager.VECTOR_FIELD)
                .withVectors(Collections.singletonList(toList(embedded)))
                .withTopK(request.normalizedTopK()).withMetricType(MetricType.L2)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withOutFields(List.of("chunkId", "documentId", "documentType", "experimentType",
                        "title", "source", "content"))
                .withParams("{\"nprobe\":10}");
        String expression = filterBuilder.build(request.documentType(), request.experimentType());
        if (!expression.isBlank()) search.withExpr(expression);
        R<SearchResults> response = client.search(search.build());
        if (response.getStatus() != 0) throw new IllegalStateException("WavePilot search failed: " + response.getMessage());

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<KnowledgeSearchResult> results = new ArrayList<>();
        int rows = wrapper.getRowRecords(0).size();
        for (int index = 0; index < rows; index++) {
            String chunkId = stringField(wrapper, "chunkId", index);
            String documentId = stringField(wrapper, "documentId", index);
            float distance = wrapper.getIDScore(0).get(index).getScore();
            results.add(new KnowledgeSearchResult(chunkId, documentId,
                    stringField(wrapper, "title", index), stringField(wrapper, "source", index),
                    stringField(wrapper, "content", index), 1.0d / (1.0d + Math.max(0, distance)),
                    DocumentType.valueOf(stringField(wrapper, "documentType", index)),
                    ExperimentType.valueOf(stringField(wrapper, "experimentType", index)),
                    "KB[" + documentId + "/" + chunkId + "]"));
        }
        return results;
    }

    @Override
    public String storageDescription() {
        return "Milvus collection " + schemaManager.collectionName();
    }

    private synchronized void initialize() {
        if (initialized) return;
        schemaManager.ensureCollection(client);
        R<RpcStatus> loaded = client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(schemaManager.collectionName())
                .withSyncLoad(Boolean.TRUE).build());
        if (loaded.getStatus() != 0 && loaded.getStatus() != 65535) {
            throw new IllegalStateException("Could not load WavePilot collection: " + loaded.getMessage());
        }
        initialized = true;
    }

    private List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        return values;
    }

    private String stringField(SearchResultsWrapper wrapper, String field, int index) {
        Object value = wrapper.getFieldData(field, 0).get(index);
        return value == null ? "" : value.toString();
    }
}
