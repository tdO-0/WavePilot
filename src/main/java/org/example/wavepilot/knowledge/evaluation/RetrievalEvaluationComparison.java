package org.example.wavepilot.knowledge.evaluation;

import org.example.wavepilot.knowledge.retrieval.RetrievalStrategy;

import java.util.Map;

public record RetrievalEvaluationComparison(
        RetrievalStrategy baseline,
        RetrievalStrategy candidate,
        Map<String, Double> metricDeltas,
        boolean measuredImprovement,
        String interpretation) {
    public RetrievalEvaluationComparison {
        metricDeltas = metricDeltas == null ? Map.of() : Map.copyOf(metricDeltas);
    }
}
