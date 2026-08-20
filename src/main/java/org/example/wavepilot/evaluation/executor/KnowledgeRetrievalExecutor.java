package org.example.wavepilot.evaluation.executor;

import org.example.wavepilot.evaluation.EvaluationCase;
import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationDataset;
import org.example.wavepilot.evaluation.EvaluationModel;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Searches the evaluation corpus through the real knowledge repository. The corpus chunks are
 * seeded idempotently by documentId before the query, so the case is deterministic offline.
 */
@Component
public class KnowledgeRetrievalExecutor implements EvaluationCaseExecutor {

    private final WavePilotKnowledgeRepository knowledgeRepository;
    private final EvaluationDataset dataset;

    public KnowledgeRetrievalExecutor(WavePilotKnowledgeRepository knowledgeRepository,
                                      EvaluationDataset dataset) {
        this.knowledgeRepository = knowledgeRepository;
        this.dataset = dataset;
    }

    @Override
    public EvaluationCaseResult execute(EvaluationCase evalCase, EvaluationModel model) {
        try {
            knowledgeRepository.upsertDocument(dataset.knowledgeChunks());
            List<KnowledgeSearchResult> results = knowledgeRepository.search(
                    new KnowledgeSearchRequest(evalCase.input(), 3, null, null));
            if (results.isEmpty()) {
                return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(),
                        evalCase.description(), evalCase.input(), evalCase.expectedResult(),
                        evalCase.expectedTool(), evalCase.forbiddenTools(), evalCase.expectedStatus(),
                        evalCase.expectedFields(), evalCase.tags(), false, "NO_HIT",
                        null, "knowledge search returned no results for: " + evalCase.input());
            }
            String topContent = results.get(0).content();
            boolean passed = topContent.contains(evalCase.expectedResult());
            return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(),
                    evalCase.description(), evalCase.input(), evalCase.expectedResult(),
                    evalCase.expectedTool(), evalCase.forbiddenTools(), evalCase.expectedStatus(),
                    evalCase.expectedFields(), evalCase.tags(), passed,
                    passed ? "HIT: " + topContent : "MISS: top hit was " + topContent,
                    null, passed ? null : "top hit does not contain expected content "
                    + evalCase.expectedResult());
        } catch (RuntimeException e) {
            return EvaluationCaseResult.failed(evalCase, "Knowledge retrieval failed: " + e.getMessage());
        }
    }
}
