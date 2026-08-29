package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.retrieval.QueryType;
import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalMetricCalculatorTest {
    @Test
    void computesRecallPrecisionMrrNdcgAndCitationFromActualRanks() {
        RetrievalEvaluationCase evalCase = new RetrievalEvaluationCase("case", "query", Set.of("REL"),
                Set.of(), QueryType.THEORY, DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, 3);
        KnowledgeSearchResult irrelevant = result("NO", "DOC-NO", "KB[DOC-NO/NO]");
        KnowledgeSearchResult relevant = result("REL", "DOC-REL", "KB[DOC-REL/REL]");

        RetrievalCaseResult result = new RetrievalMetricCalculator().calculate(evalCase,
                RetrievalStrategy.HYBRID_RRF, QueryType.THEORY, List.of(irrelevant, relevant));

        assertEquals(0.0, result.recallAt1(), 1.0e-12);
        assertEquals(1.0, result.recallAt3(), 1.0e-12);
        assertEquals(1.0, result.recallAt5(), 1.0e-12);
        assertEquals(1.0 / 3.0, result.precisionAt3(), 1.0e-12);
        assertEquals(1.0 / 5.0, result.precisionAt5(), 1.0e-12);
        assertEquals(0.5, result.reciprocalRank(), 1.0e-12);
        assertEquals(1.0 / (Math.log(3) / Math.log(2)), result.ndcgAt3(), 1.0e-12);
        assertEquals(1.0 / (Math.log(3) / Math.log(2)), result.ndcgAt5(), 1.0e-12);
        assertEquals(1.0, result.citationHitRate(), 1.0e-12);
        assertEquals(1.0, result.hardNegativeRejectionRate(), 1.0e-12);
    }

    @Test
    void computesHardNegativeRejectionFromActualTopFive() {
        RetrievalEvaluationCase evalCase = new RetrievalEvaluationCase("hard-negative", "query",
                QueryType.THEORY, Set.of("REL"), Set.of(), Set.of("HN-RETRIEVED", "HN-REJECTED"),
                DocumentType.THEORY, ExperimentType.POLAR_CODE_K_IDENTIFICATION, 5, false);

        RetrievalCaseResult result = new RetrievalMetricCalculator().calculate(evalCase,
                RetrievalStrategy.HYBRID_RRF, QueryType.THEORY, List.of(
                        result("REL", "DOC-REL", "KB[DOC-REL/REL]"),
                        result("HN-RETRIEVED", "DOC-HN", "KB[DOC-HN/HN-RETRIEVED]")));

        assertEquals(0.5, result.hardNegativeRejectionRate(), 1.0e-12);
    }

    private KnowledgeSearchResult result(String chunk, String document, String citation) {
        return new KnowledgeSearchResult(chunk, document, "title", "test://" + document,
                "content", 1, DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, citation,
                "section", Map.of(), "test");
    }
}
