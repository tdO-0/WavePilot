package org.example.wavepilot.runner;

import org.example.wavepilot.experiment.model.ExperimentJob;

import java.util.List;

public interface ExperimentRunner {
    RunnerSubmission submit(ExperimentJob job);
    RunnerStatus getStatus(String externalJobId);
    void cancel(String externalJobId);
    List<ProducedArtifact> collectArtifacts(String externalJobId);
    String runnerType();
    String experimentTemplateVersion();
}
