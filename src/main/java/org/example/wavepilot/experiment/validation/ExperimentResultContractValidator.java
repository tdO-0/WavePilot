package org.example.wavepilot.experiment.validation;

import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Type-specific result contract: what accuracy.csv columns, summary fields and binary
 * artifacts a real execution of this type must produce. Registered code-level per type;
 * ResultValidator dispatches on the type declared by the job's template.
 */
public interface ExperimentResultContractValidator {

    ExperimentType experimentType();

    void validate(ExperimentJob job, Map<ArtifactType, Path> artifacts, List<String> errors);
}
