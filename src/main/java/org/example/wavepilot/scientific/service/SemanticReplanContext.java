package org.example.wavepilot.scientific.service;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.Observation;
import org.example.wavepilot.scientific.model.ReplanDecision;
import org.example.wavepilot.scientific.model.VerificationResult;

import java.util.List;

public record SemanticReplanContext(
        ExperimentGoal goal,
        ExperimentSpec currentSpec,
        Observation observation,
        VerificationResult verification,
        List<KnowledgeSearchResult> retrievedEvidence,
        List<ReplanDecision> previousChanges) {
    public SemanticReplanContext {
        retrievedEvidence = retrievedEvidence == null ? List.of() : List.copyOf(retrievedEvidence);
        previousChanges = previousChanges == null ? List.of() : List.copyOf(previousChanges);
    }
}
