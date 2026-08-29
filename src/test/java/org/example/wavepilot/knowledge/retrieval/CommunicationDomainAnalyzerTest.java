package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationDomainAnalyzerTest {
    @Test
    void preservesAcronymsAndNormalizesEbN0AndIdentifiers() throws Exception {
        try (LuceneBm25SparseRetriever retriever = new LuceneBm25SparseRetriever()) {
            List<String> terms = retriever.analyzeTerms(
                    "BPSK AWGN BER BLER Eb/N0 decodeFrame noise_variance");

            assertTrue(terms.containsAll(List.of("bpsk", "awgn", "ber", "bler", "ebn0")), terms::toString);
            assertTrue(terms.contains("decodeframe"), terms::toString);
            assertTrue(terms.contains("decode") && terms.contains("frame"), terms::toString);
            assertTrue(terms.contains("noise_variance") || terms.contains("noisevariance"), terms::toString);
        }
    }

    @Test
    void retrievesChineseEnglishAcronymAndMixedQueries() throws Exception {
        try (LuceneBm25SparseRetriever retriever = new LuceneBm25SparseRetriever()) {
            retriever.upsertDocument(List.of(chunk("CN", "高斯白噪声信道中的二进制相移键控检测")));
            retriever.upsertDocument(List.of(chunk("EN", "BPSK coherent detector over an AWGN channel")));
            retriever.upsertDocument(List.of(chunk("PARAM", "decodeFrame uses noise_variance and ebN0Db parameters")));

            assertEquals("CN", first(retriever, "白噪声信道 二进制检测"));
            assertEquals("EN", first(retriever, "BPSK AWGN coherent detector"));
            assertEquals("PARAM", first(retriever, "decodeFrame noise_variance"));
            assertEquals("PARAM", first(retriever, "MATLAB 的 ebN0Db parameter"));
        }
    }

    private String first(LuceneBm25SparseRetriever retriever, String query) {
        return retriever.search(new KnowledgeSearchRequest(query, 5, null,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION), 5).get(0).evidence().chunkId();
    }

    private KnowledgeChunk chunk(String id, String content) {
        return new KnowledgeChunk(id, new KnowledgeDocumentMetadata("DOC-" + id,
                DocumentType.MATLAB_GUIDE, ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                id, "test://" + id, "1", Instant.EPOCH), content, "section", Map.of());
    }
}
