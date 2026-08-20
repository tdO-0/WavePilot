package org.example.wavepilot.replay;

import org.example.wavepilot.experiment.model.ExperimentType;

import java.util.List;

/** Polar-k reproducibility metrics: accuracy (max and mean abs diff), MAE and bias (max diff). */
public class PolarKComparisonMetrics implements ExperimentComparisonMetrics {

    @Override
    public ExperimentType experimentType() {
        return ExperimentType.POLAR_CODE_K_IDENTIFICATION;
    }

    @Override
    public List<Metric> metrics() {
        return List.of(new Metric("accuracy", true), new Metric("mae", false), new Metric("bias", false));
    }
}
