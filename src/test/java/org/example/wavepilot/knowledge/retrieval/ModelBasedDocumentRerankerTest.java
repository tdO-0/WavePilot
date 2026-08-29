package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelBasedDocumentRerankerTest {
    @Test
    void validModelPermutationReordersOnlyExistingCandidatesAndPreservesCitation() {
        RetrievalCandidate first = candidate("A", 2);
        RetrievalCandidate second = candidate("B", 1);
        ModelBasedDocumentReranker reranker = new ModelBasedDocumentReranker(
                (query, candidates) -> List.of("B", "A"));

        List<RetrievalCandidate> result = reranker.rerank("semantic query", List.of(first, second));

        assertEquals(List.of("B", "A"), result.stream().map(v -> v.evidence().chunkId()).toList());
        assertEquals("KB[DOC-B/B]", result.get(0).evidence().citation());
        assertEquals("test://B", result.get(0).evidence().source());
        assertEquals("model", reranker.lastMode());
    }

    @Test
    void unknownDuplicateMissingOrProviderFailureFallsBackDeterministically() {
        List<RetrievalCandidate> candidates = List.of(candidate("A", 2), candidate("B", 1));
        ModelBasedDocumentReranker unknown = new ModelBasedDocumentReranker(
                (query, values) -> List.of("INVENTED", "A"));
        ModelBasedDocumentReranker duplicate = new ModelBasedDocumentReranker(
                (query, values) -> List.of("A", "A"));
        ModelBasedDocumentReranker failing = new ModelBasedDocumentReranker((query, values) -> {
            throw new IllegalStateException("provider down");
        });

        assertEquals(List.of("A", "B"), ids(unknown.rerank("A", candidates)));
        assertEquals(List.of("A", "B"), ids(duplicate.rerank("A", candidates)));
        assertEquals(List.of("A", "B"), ids(failing.rerank("A", candidates)));
        assertEquals("model-fallback-deterministic", failing.lastMode());
    }

    private List<String> ids(List<RetrievalCandidate> values) {
        return values.stream().map(value -> value.evidence().chunkId()).toList();
    }

    private RetrievalCandidate candidate(String id, double score) {
        KnowledgeSearchResult evidence = new KnowledgeSearchResult(id, "DOC-" + id, id,
                "test://" + id, id + " relevant content", score, DocumentType.THEORY,
                ExperimentType.POLAR_CODE_K_IDENTIFICATION, "KB[DOC-" + id + "/" + id + "]",
                "section", Map.of("preserved", "true"), "HYBRID_RRF");
        return new RetrievalCandidate(evidence, score);
    }
}
