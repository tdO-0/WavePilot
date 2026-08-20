package org.example.wavepilot.knowledge;

import org.example.wavepilot.config.KnowledgeUploadProperties;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeController.class)
class KnowledgeControllerContractTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    KnowledgeService knowledgeService;

    @MockBean
    KnowledgeUploadProperties uploadConfig;

    @Test
    void uploadsCommunicationDocumentWithMetadata() throws Exception {
        when(uploadConfig.getMaxSizeBytes()).thenReturn(5_242_880L);
        when(uploadConfig.getAllowedExtensions()).thenReturn("txt,md");
        when(knowledgeService.ingest(any(), eq(DocumentType.THEORY),
                eq(ExperimentType.POLAR_CODE_K_IDENTIFICATION), eq("Polar theory"),
                eq("internal://polar"), eq("v1")))
                .thenReturn(new KnowledgeService.KnowledgeIngestResult("KB-DOC-1", 2,
                        DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                        "Polar theory", "internal://polar", "v1", "Milvus wavepilot_knowledge_v1"));
        MockMultipartFile file = new MockMultipartFile("file", "polar.md", "text/markdown",
                "# Polar theory".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/wavepilot/knowledge/upload").file(file)
                        .param("documentType", "THEORY")
                        .param("experimentType", "POLAR_CODE_K_IDENTIFICATION")
                        .param("title", "Polar theory").param("source", "internal://polar").param("version", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("KB-DOC-1"))
                .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void searchReturnsStableCitation() throws Exception {
        KnowledgeSearchResult result = new KnowledgeSearchResult("CH-1", "DOC-1", "Polar theory",
                "internal://polar", "content", 0.9, DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, "KB[DOC-1/CH-1]");
        when(knowledgeService.search(any(KnowledgeSearchRequest.class))).thenReturn(List.of(result));

        mockMvc.perform(post("/api/wavepilot/knowledge/search")
                        .contentType("application/json")
                        .content("{\"query\":\"polar\",\"topK\":3,\"documentType\":\"THEORY\","
                                + "\"experimentType\":\"POLAR_CODE_K_IDENTIFICATION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].citation").value("KB[DOC-1/CH-1]"));
    }
}
