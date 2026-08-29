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
        "wavepilot.scientific.run-store=target/retrieval-eval-runs",
        "wavepilot.scientific.execution-ledger-store=target/retrieval-eval-ledger"
})
class RetrievalEvaluationReportTest {
    @Autowired RetrievalEvaluationService service;
    @Autowired ArtifactRegistry artifactRegistry;

    @Test
    void evaluatesEightyBilingualCasesAndFiveStrategiesWithGeneratedComparisons() {
        RetrievalEvaluationReport report = service.run();

        assertEquals(80, report.caseCount());
        assertEquals(5, report.metrics().size());
        assertEquals(400, report.caseResults().size());
        assertEquals(4, report.queryTypeCounts().size());
        assertTrue(report.queryTypeCounts().values().stream().allMatch(count -> count == 20));
        assertEquals(3, report.comparisons().size());
        assertTrue(report.metrics().values().stream().allMatch(metric -> metric.caseCount() == 80));
        assertTrue(report.metrics().values().stream().allMatch(metric -> metric.recallAtK() >= 0
                && metric.recallAtK() <= 1 && metric.precisionAtK() >= 0 && metric.precisionAtK() <= 1));
        assertFalse(service.renderMarkdown(report).isBlank());
        assertTrue(artifactRegistry.listByJobId(report.evaluationId()).stream()
                .anyMatch(value -> value.type() == ArtifactType.RETRIEVAL_EVAL_JSON));
        assertTrue(artifactRegistry.listByJobId(report.evaluationId()).stream()
                .anyMatch(value -> value.type() == ArtifactType.RETRIEVAL_EVAL_MARKDOWN));
        assertTrue(artifactRegistry.listByJobId(report.evaluationId()).stream()
                .anyMatch(value -> value.type() == ArtifactType.RETRIEVAL_EVAL_COMPARISON));

        report.metrics().forEach((strategy, metric) -> System.out.printf(
                "RETRIEVAL_EVAL %s r1=%.6f r3=%.6f r5=%.6f p3=%.6f p5=%.6f mrr=%.6f ndcg5=%.6f citation=%.6f hardNeg=%.6f latency=%.3f%n",
                strategy, metric.recallAt1(), metric.recallAt3(), metric.recallAt5(),
                metric.precisionAt3(), metric.precisionAt5(), metric.mrr(), metric.ndcgAt5(),
                metric.citationHitRate(), metric.hardNegativeRejectionRate(), metric.averageLatencyMillis()));
    }
}
