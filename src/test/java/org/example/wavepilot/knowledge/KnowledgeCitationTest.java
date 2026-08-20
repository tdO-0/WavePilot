package org.example.wavepilot.knowledge;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeCitationTest {

    @Test
    void searchResultCarriesStableDocumentAndChunkCitation() {
        KnowledgeChunk chunk = new KnowledgeChunk("KB-DOC-ABC-CH-0001",
                new KnowledgeDocumentMetadata("KB-DOC-ABC", DocumentType.THEORY,
                        ExperimentType.POLAR_CODE_K_IDENTIFICATION, "Polar theory",
                        "internal://polar", "v1", Instant.now()), "knowledge content");

        KnowledgeSearchResult result = KnowledgeSearchResult.from(chunk, 0.93);

        assertEquals("KB-DOC-ABC-CH-0001", result.chunkId());
        assertEquals("KB-DOC-ABC", result.documentId());
        assertEquals("internal://polar", result.source());
        assertEquals(DocumentType.THEORY, result.documentType());
        assertEquals(ExperimentType.POLAR_CODE_K_IDENTIFICATION, result.experimentType());
        assertEquals("KB[KB-DOC-ABC/KB-DOC-ABC-CH-0001]", result.citation());
        assertTrue(result.similarityScore() > 0.9);
    }
}
