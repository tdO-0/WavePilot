package org.example.wavepilot.knowledge.repository;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WavePilotMilvusSchemaManager {

    public static final String PRODUCTION_COLLECTION_NAME = "wavepilot_knowledge_v1";
    public static final String VECTOR_FIELD = "vector";
    static final Set<String> REQUIRED_FIELDS = Set.of(
            "chunkId", "documentId", "documentType", "experimentType", "title", "source",
            "version", "createdAt", "content", VECTOR_FIELD);

    private final String collectionName;

    public WavePilotMilvusSchemaManager(
            @Value("${wavepilot.knowledge.collection:wavepilot_knowledge_v1}") String collectionName) {
        if (collectionName == null || !collectionName.matches("[A-Za-z][A-Za-z0-9_]{0,254}")) {
            throw new IllegalArgumentException("Invalid WavePilot Milvus collection name");
        }
        this.collectionName = collectionName;
    }

    public String collectionName() {
        return collectionName;
    }

    public synchronized void ensureCollection(MilvusServiceClient client) {
        R<Boolean> exists = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        requireSuccess(exists, "check WavePilot collection");
        if (Boolean.TRUE.equals(exists.getData())) {
            validateExistingSchema(client);
            return;
        }

        List<FieldType> fields = List.of(
                varchar("chunkId", 256, true),
                varchar("documentId", 256, false),
                varchar("documentType", 64, false),
                varchar("experimentType", 128, false),
                varchar("title", 1024, false),
                varchar("source", 2048, false),
                varchar("version", 128, false),
                varchar("createdAt", 64, false),
                varchar("content", WavePilotMilvusConstants.CONTENT_MAX_LENGTH, false),
                FieldType.newBuilder().withName(VECTOR_FIELD).withDataType(DataType.FloatVector)
                        .withDimension(WavePilotMilvusConstants.VECTOR_DIMENSION).build());
        CollectionSchemaParam.Builder schema = CollectionSchemaParam.newBuilder().withEnableDynamicField(false);
        fields.forEach(schema::addFieldType);
        R<RpcStatus> created = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("WavePilot communication experiment knowledge v1")
                .withSchema(schema.build())
                .withShardsNum(WavePilotMilvusConstants.DEFAULT_SHARD_COUNT)
                .build());
        requireSuccess(created, "create WavePilot collection");

        R<RpcStatus> indexed = client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName).withFieldName(VECTOR_FIELD)
                .withIndexType(IndexType.IVF_FLAT).withMetricType(MetricType.L2)
                .withExtraParam("{\"nlist\":128}").withSyncMode(Boolean.FALSE).build());
        requireSuccess(indexed, "create WavePilot vector index");
    }

    private void validateExistingSchema(MilvusServiceClient client) {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        requireSuccess(response, "describe WavePilot collection");
        Set<String> actual = new DescCollResponseWrapper(response.getData()).getFields().stream()
                .map(FieldType::getName).collect(Collectors.toSet());
        if (!actual.containsAll(REQUIRED_FIELDS)) {
            throw new IllegalStateException("Existing " + collectionName
                    + " schema is incompatible; no automatic mutation was attempted. Missing="
                    + REQUIRED_FIELDS.stream().filter(field -> !actual.contains(field)).toList());
        }
    }

    private FieldType varchar(String name, int maxLength, boolean primaryKey) {
        return FieldType.newBuilder().withName(name).withDataType(DataType.VarChar)
                .withMaxLength(maxLength).withPrimaryKey(primaryKey).build();
    }

    private void requireSuccess(R<?> response, String operation) {
        if (response == null || response.getStatus() != 0) {
            throw new IllegalStateException("Could not " + operation + ": "
                    + (response == null ? "empty Milvus response" : response.getMessage()));
        }
    }
}
