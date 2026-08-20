package org.example.wavepilot;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;

import java.time.Instant;
import java.util.List;

public final class WavePilotTestFixtures {

    private WavePilotTestFixtures() {
    }

    public static ExperimentSpec validSpec() {
        return new ExperimentSpec(
                ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64),
                0.0,
                0.02,
                0.01,
                20,
                10,
                20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG),
                "test polar code K identification");
    }

    public static ExperimentJob job(String jobId) {
        ExperimentSpec spec = validSpec();
        ExperimentPlan plan = new ExperimentPlan("PLAN-TEST", spec, "mock-polar-k-v1", 6,
                List.of("RUN_EXPERIMENT", "VALIDATE_RESULT"), Instant.now());
        return new ExperimentJob(jobId, spec, plan);
    }
}
