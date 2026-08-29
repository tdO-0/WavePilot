package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeDocumentMetadata;
import org.example.wavepilot.knowledge.retrieval.QueryType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RetrievalEvaluationDataset {
    public static final String NAME = "wavepilot-hybrid-retrieval-v1";
    private static final ExperimentType TYPE = ExperimentType.POLAR_CODE_K_IDENTIFICATION;

    private final List<KnowledgeChunk> chunks = List.of(
            chunk("RET-CH-001", "RET-DOC-POLAR", DocumentType.THEORY, "Polar generator matrix",
                    "Theory", "Polar code encoding uses the Kronecker generator matrix G for codeword construction."),
            chunk("RET-CH-002", "RET-DOC-PARAM", DocumentType.MATLAB_GUIDE, "Monte Carlo parameters",
                    "Parameters", "Monte Carlo sample count and repetition count should be selected within the registered parameter range."),
            chunk("RET-CH-003", "RET-DOC-DIM", DocumentType.FAILURE_CASE, "MATLAB dimension mismatch",
                    "Troubleshooting", "MATLAB dimension mismatch error is resolved by checking matrix shape before multiplication."),
            chunk("RET-CH-004", "RET-DOC-RECIPE", DocumentType.EXPERIMENT_RECIPE, "Polar experiment recipe",
                    "Workflow", "Polar experiment workflow: validate parameters, execute the approved template, then verify artifacts."),
            chunk("RET-CH-005", "RET-DOC-BEC", DocumentType.THEORY, "BEC reliability order",
                    "Theory", "BEC erasure channel reliability order can be computed recursively for polar subchannels."),
            chunk("RET-CH-006", "RET-DOC-NAN", DocumentType.FAILURE_CASE, "NaN CSV output",
                    "Troubleshooting", "NaN in result CSV must fail deterministic result validation and trigger parameter troubleshooting."),
            chunk("RET-CH-007", "RET-DOC-DISTRACTOR", DocumentType.THEORY, "Office policy",
                    "Unrelated", "Office room booking and holiday policy have no communication experiment content."),
            chunk("RET-CH-008", "RET-DOC-OTHER-RECIPE", DocumentType.EXPERIMENT_RECIPE, "Generic plotting",
                    "Workflow", "Plot export workflow controls chart size and image file format."));

    private final List<RetrievalEvaluationCase> cases = List.of(
            eval("R-001", "polar generator matrix encoding", "RET-CH-001", QueryType.THEORY, DocumentType.THEORY),
            eval("R-002", "Monte Carlo sample count parameter range", "RET-CH-002", QueryType.PARAMETER, DocumentType.MATLAB_GUIDE),
            eval("R-003", "MATLAB dimension mismatch error troubleshooting", "RET-CH-003", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE),
            eval("R-004", "polar experiment workflow recipe", "RET-CH-004", QueryType.EXPERIMENT_GUIDANCE, DocumentType.EXPERIMENT_RECIPE),
            eval("R-005", "BEC erasure channel reliability order", "RET-CH-005", QueryType.THEORY, DocumentType.THEORY),
            eval("R-006", "NaN result CSV failure debug", "RET-CH-006", QueryType.TROUBLESHOOTING, DocumentType.FAILURE_CASE));

    public String name() { return NAME; }
    public List<KnowledgeChunk> chunks() { return chunks; }
    public List<RetrievalEvaluationCase> cases() { return cases; }

    private static RetrievalEvaluationCase eval(String id, String query, String chunkId,
                                                 QueryType type, DocumentType filter) {
        return new RetrievalEvaluationCase(id, query, Set.of(chunkId), Set.of(), type, filter, TYPE, 3);
    }

    private static KnowledgeChunk chunk(String chunkId, String documentId, DocumentType documentType,
                                        String title, String section, String content) {
        return new KnowledgeChunk(chunkId, new KnowledgeDocumentMetadata(documentId, documentType,
                TYPE, title, "classpath://retrieval-eval/" + documentId, "1.0.0", Instant.EPOCH),
                content, section, Map.of("dataset", NAME));
    }
}
