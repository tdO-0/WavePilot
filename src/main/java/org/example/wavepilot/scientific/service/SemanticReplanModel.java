package org.example.wavepilot.scientific.service;

import org.example.wavepilot.experiment.model.ExperimentSpec;

public interface SemanticReplanModel {
    ExperimentSpec propose(SemanticReplanContext context);
}
