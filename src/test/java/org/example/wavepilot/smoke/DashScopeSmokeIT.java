package org.example.wavepilot.smoke;

import org.example.wavepilot.agent.WavePilotChatService;
import org.example.wavepilot.agent.spec.ExperimentSpecParseResult;
import org.example.wavepilot.agent.spec.ExperimentSpecParseStatus;
import org.example.wavepilot.agent.spec.ExperimentSpecParser;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.knowledge.WavePilotEmbeddingService;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dashscope-smoke")
@Import(DashScopeSmokeIT.MinimalSmokeKnowledgeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashScopeSmokeIT {

    @Autowired private WavePilotEmbeddingService embeddingService;
    @Autowired private ExperimentSpecParser specParser;
    @Autowired private WavePilotChatService chatService;
    @Autowired private ExperimentService experimentService;

    @BeforeAll
    void requireEnvironmentKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertTrue(apiKey != null && !apiKey.isBlank(),
                "dashscope-smoke requires DASHSCOPE_API_KEY in the process environment");
    }

    @Test
    @Order(1)
    void chineseEmbeddingUsesRealDashScopeAndHas1024Dimensions() {
        float[] chineseEmbedding = embeddingService.embed("极化码码维数识别");
        assertNotNull(chineseEmbedding);
        assertEquals(1024, chineseEmbedding.length);
        System.out.printf("DASHSCOPE_EMBEDDING_SMOKE provider=%s dimensions=%d%n",
                embeddingService.providerDescription(), chineseEmbedding.length);
    }

    @Test
    @Order(2)
    void realModelParsesOneCompleteSpec() {
        ExperimentSpecParseResult complete = specParser.parse(
                "做极化码码维数识别实验，码长32和64，误码率从0到0.02，步长0.01，每组20帧，运行10次蒙特卡洛实验");
        assertEquals(ExperimentSpecParseStatus.COMPLETE, complete.parseStatus(), complete.toString());
        System.out.printf("DASHSCOPE_SPEC_SMOKE complete=%s%n", complete.parseStatus());
    }

    @Test
    @Order(3)
    void realAgentSearchesKnowledgeAndSubmitsOneMockExperiment() {
        List<String> before = experimentService.list().stream().map(ExperimentJob::getJobId).toList();
        WavePilotChatService.ChatResponse response = chatService.chat("dashscope-smoke-agent", """
                先调用 searchExperimentKnowledge 检索 THEORY 类型的极化码码维数识别知识，
                experimentType 使用 POLAR_CODE_K_IDENTIFICATION，并在回答中保留 KB 引用。
                然后通过受控工具创建、校验并提交这个实验：极化码码维数识别，码长32，
                误码率0到0.01，步长0.01，每组10帧，运行2次蒙特卡洛实验。
                提交后返回 jobId，并明确说明 mock=true。
                """);

        List<ExperimentJob> created = experimentService.list().stream()
                .filter(job -> !before.contains(job.getJobId())).toList();
        assertEquals(1, created.size(), response.answer());
        assertTrue(response.mockRunner());
        String lower = response.answer().toLowerCase();
        assertTrue(lower.contains("mock") || response.answer().contains("模拟数据"), response.answer());
        assertTrue(response.answer().contains(created.get(0).getJobId()), response.answer());
        assertTrue(response.answer().contains("KB[KB-SMOKE-THEORY/KB-SMOKE-THEORY-CH-0000]"),
                response.answer());
        System.out.printf("DASHSCOPE_AGENT_SMOKE search=true jobId=%s mock=true%n",
                created.get(0).getJobId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MinimalSmokeKnowledgeConfiguration {

        @Bean
        @Primary
        WavePilotKnowledgeRepository minimalSmokeKnowledgeRepository() {
            return new WavePilotKnowledgeRepository() {
                @Override
                public void upsertDocument(List<KnowledgeChunk> chunks) {
                    throw new UnsupportedOperationException("Minimal DashScope smoke is read-only");
                }

                @Override
                public List<KnowledgeSearchResult> search(KnowledgeSearchRequest request) {
                    if (request.documentType() != DocumentType.THEORY
                            || request.experimentType() != ExperimentType.POLAR_CODE_K_IDENTIFICATION) {
                        throw new IllegalArgumentException("Agent must use the requested metadata filters");
                    }
                    return List.of(new KnowledgeSearchResult(
                            "KB-SMOKE-THEORY-CH-0000",
                            "KB-SMOKE-THEORY",
                            "极化码码维数识别原理",
                            "smoke://dashscope/minimal-knowledge",
                            "极化码码维数识别实验需要显式记录码长、误码率范围、样本数和蒙特卡洛次数。",
                            1.0d,
                            DocumentType.THEORY,
                            ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                            "KB[KB-SMOKE-THEORY/KB-SMOKE-THEORY-CH-0000]"));
                }

                @Override
                public String storageDescription() {
                    return "fixed read-only repository for minimal DashScope smoke";
                }
            };
        }
    }
}
