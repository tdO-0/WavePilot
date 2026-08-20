package org.example.wavepilot.knowledge.repository;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.WavePilotEmbeddingService;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorMetadataFilterTest {

    @Test
    void buildsSafeEnumOnlyMilvusExpression() {
        String expression = new MilvusMetadataFilterBuilder().build(DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION);
        assertEquals("documentType == \"THEORY\" && experimentType == \"POLAR_CODE_K_IDENTIFICATION\"", expression);
    }

    @Test
    void buildsIndependentDocumentTypeFilters() {
        MilvusMetadataFilterBuilder builder = new MilvusMetadataFilterBuilder();

        assertEquals("documentType == \"THEORY\"", builder.build(DocumentType.THEORY, null));
        assertEquals("documentType == \"EXPERIMENT_RECIPE\"",
                builder.build(DocumentType.EXPERIMENT_RECIPE, null));
    }

    @Test
    void buildsIndependentExperimentTypeFilter() {
        assertEquals("experimentType == \"POLAR_CODE_K_IDENTIFICATION\"",
                new MilvusMetadataFilterBuilder().build(null,
                        ExperimentType.POLAR_CODE_K_IDENTIFICATION));
    }

    @Test
    void wavePilotKnowledgeSchemaCannotStoreJobsOrArtifactRecords() {
        assertFalse(WavePilotMilvusSchemaManager.REQUIRED_FIELDS.contains("jobId"));
        assertFalse(WavePilotMilvusSchemaManager.REQUIRED_FIELDS.contains("status"));
        assertFalse(WavePilotMilvusSchemaManager.REQUIRED_FIELDS.contains("artifactId"));
        assertFalse(WavePilotMilvusSchemaManager.REQUIRED_FIELDS.contains("artifactPath"));
    }

    @Test
    void schemaManagerUsesConfiguredCollectionAndRejectsUnsafeNames() {
        WavePilotMilvusSchemaManager manager = new WavePilotMilvusSchemaManager(
                "wavepilot_knowledge_smoke_v1");

        assertEquals("wavepilot_knowledge_smoke_v1", manager.collectionName());
        assertThrows(IllegalArgumentException.class, () -> new WavePilotMilvusSchemaManager(""));
        assertThrows(IllegalArgumentException.class, () -> new WavePilotMilvusSchemaManager("1wavepilot"));
        assertThrows(IllegalArgumentException.class,
                () -> new WavePilotMilvusSchemaManager("unsafe-name"));
    }

    @Test
    void metadataFilterExcludesMoreSimilarWrongDocumentType() {
        InMemoryWavePilotKnowledgeRepository repository = new InMemoryWavePilotKnowledgeRepository(new FakeEmbedding());
        repository.upsertDocument(List.of(chunk("THEORY-CH", "THEORY-DOC", DocumentType.THEORY,
                "theory moderately similar")));
        repository.upsertDocument(List.of(chunk("RECIPE-CH", "RECIPE-DOC", DocumentType.EXPERIMENT_RECIPE,
                "recipe exact match")));

        List<KnowledgeSearchResult> theory = repository.search(new KnowledgeSearchRequest(
                "polar query", 5, DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION));

        assertEquals(1, theory.size());
        assertEquals(DocumentType.THEORY, theory.get(0).documentType());
        assertEquals("THEORY-CH", theory.get(0).chunkId());
    }

    private KnowledgeChunk chunk(String chunkId, String documentId, DocumentType type, String content) {
        return new KnowledgeChunk(chunkId, new KnowledgeDocumentMetadata(documentId, type,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, content, "test://" + documentId,
                "v1", Instant.now()), content);
    }

    private static final class FakeEmbedding implements WavePilotEmbeddingService {
        @Override
        public float[] embed(String text) {
            if (text.contains("recipe exact") || text.contains("polar query")) return new float[]{1, 0};
            return new float[]{0.8f, 0.2f};
        }

        @Override
        public String providerDescription() { return "fake"; }
    }
}
