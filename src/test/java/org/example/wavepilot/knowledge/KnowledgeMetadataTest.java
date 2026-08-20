package org.example.wavepilot.knowledge;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeMetadataTest {

    private final KnowledgeIdFactory idFactory = new KnowledgeIdFactory();

    @Test
    void createsStableDocumentAndChunkIdsFromMetadata() {
        String first = idFactory.documentId(DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, "Polar K theory", "3GPP/source", "v1");
        String second = idFactory.documentId(DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, "  Polar   K theory ", "3GPP/source", "v1");

        assertEquals(first, second);
        assertEquals(first + "-CH-0003", idFactory.chunkId(first, 3));
    }

    @Test
    void metadataContainsEveryRequiredField() {
        KnowledgeDocumentMetadata metadata = new KnowledgeDocumentMetadata("KB-DOC-123",
                DocumentType.EXPERIMENT_RECIPE, ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                "Polar recipe", "internal://recipe", "1.0", Instant.now());

        assertNotNull(metadata.documentId());
        assertNotNull(metadata.documentType());
        assertNotNull(metadata.experimentType());
        assertTrue(!metadata.title().isBlank() && !metadata.source().isBlank() && !metadata.version().isBlank());
        assertNotNull(metadata.createdAt());
    }
}
