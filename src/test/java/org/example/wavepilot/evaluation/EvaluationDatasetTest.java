package org.example.wavepilot.evaluation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationDatasetTest {

    private final EvaluationDataset dataset = new EvaluationDataset();

    @Test
    void datasetHasAtLeastTwentyFixedCases() {
        List<EvaluationCase> cases = dataset.require(EvaluationDataset.DEFAULT_DATASET);
        assertTrue(cases.size() >= 20, "first release must carry at least 20 fixed cases");
        assertEquals(24, cases.size());
    }

    @Test
    void datasetCoversAllTwelveCaseTypes() {
        assertEquals(Set.of(EvaluationCaseType.values()), dataset.coveredCaseTypes());
    }

    @Test
    void caseIdsAreUniqueAndEveryCaseIsComplete() {
        Set<String> ids = new HashSet<>();
        for (EvaluationCase evalCase : dataset.require(EvaluationDataset.DEFAULT_DATASET)) {
            assertTrue(ids.add(evalCase.caseId()), "duplicate caseId: " + evalCase.caseId());
            assertFalse(evalCase.caseId().isBlank());
            assertNotNull(evalCase.caseType());
            assertFalse(evalCase.description().isBlank());
            assertFalse(evalCase.input().isBlank());
            assertFalse(evalCase.expectedResult().isBlank());
            assertFalse(evalCase.expectedStatus().isBlank());
        }
    }

    @Test
    void specCasesExpectConcreteFieldsAndSecurityCasesExpectForbiddenTools() {
        for (EvaluationCase evalCase : dataset.require(EvaluationDataset.DEFAULT_DATASET)) {
            switch (evalCase.caseType()) {
                case COMPLETE_SPEC, MISSING_PARAMETER, INVALID_PARAMETER ->
                        assertFalse(evalCase.expectedFields().isEmpty(),
                                evalCase.caseId() + " must declare expected fields");
                case TOOL_SELECTION ->
                        assertFalse(evalCase.expectedTool().isBlank(),
                                evalCase.caseId() + " must declare an expected tool");
                case TOOL_SECURITY ->
                        assertFalse(evalCase.forbiddenTools().isEmpty(),
                                evalCase.caseId() + " must declare forbidden tools");
                default -> { /* platform cases need no extra contract */ }
            }
        }
    }

    @Test
    void unknownDatasetIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(EvaluationException.class,
                () -> dataset.require("not-a-dataset"));
    }

    @Test
    void knowledgeCorpusCarriesBothRetrievalTargets() {
        List<org.example.wavepilot.knowledge.model.KnowledgeChunk> chunks = dataset.knowledgeChunks();
        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("生成矩阵")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("BEC")));
    }
}
