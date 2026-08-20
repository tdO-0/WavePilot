package org.example.wavepilot.experiment.repository;

import org.example.wavepilot.experiment.model.ExperimentJob;

import java.util.List;
import java.util.Optional;

public interface ExperimentJobRepository {
    ExperimentJob save(ExperimentJob job);
    Optional<ExperimentJob> findById(String jobId);
    List<ExperimentJob> findAll();
}
