package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "wavepilot.knowledge.repository=memory",
        "wavepilot.embedding.offline=true",
        "wavepilot.artifacts.root=target/retrieval-eval-artifacts",
        "wavepilot.scientific.run-store=target/retrieval-eval-runs"
})
class RetrievalEvaluationReportTest {
    @Autowired RetrievalEvaluationService service;
    @Autowired ArtifactRegistry artifactRegistry;

    @Test
    void evaluatesAllFourStrategiesAndWritesJsonAndMarkdownFromActualResults() {
        RetrievalEvaluationReport report = service.run();

        assertEquals(6, report.caseCount());
        assertEquals(4, report.metrics().size());
        assertEquals(24, report.caseResults().size());
        assertTrue(report.metrics().values().stream().allMatch(metric -> metric.caseCount() == 6));
        assertTrue(report.metrics().values().stream().allMatch(metric -> metric.recallAtK() >= 0
                && metric.recallAtK() <= 1 && metric.precisionAtK() >= 0 && metric.precisionAtK() <= 1));
        assertTrue(report.caseResults().stream().allMatch(result ->
                result.expectedQueryType() == result.actualQueryType()));
        assertFalse(service.renderMarkdown(report).isBlank());
        assertTrue(artifactRegistry.listByJobId(report.evaluationId()).stream()
                .anyMatch(value -> value.type() == ArtifactType.RETRIEVAL_EVAL_JSON));
        assertTrue(artifactRegistry.listByJobId(report.evaluationId()).stream()
                .anyMatch(value -> value.type() == ArtifactType.RETRIEVAL_EVAL_MARKDOWN));

        report.metrics().forEach((strategy, metric) -> System.out.printf(
                "RETRIEVAL_EVAL %s recall=%.6f precision=%.6f mrr=%.6f ndcg=%.6f citation=%.6f%n",
                strategy, metric.recallAtK(), metric.precisionAtK(), metric.mrr(),
                metric.ndcgAtK(), metric.citationHitRate()));
    }
}
