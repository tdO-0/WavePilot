package org.example.wavepilot.experiment.service;

import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperimentStateMachineTest {

    private final ExperimentStateMachine stateMachine = new ExperimentStateMachine();

    @Test
    void acceptsHappyPath() {
        ExperimentJob job = WavePilotTestFixtures.job("JOB-STATE-1");

        stateMachine.transition(job, ExperimentStatus.VALIDATED, "validated");
        stateMachine.transition(job, ExperimentStatus.QUEUED, "queued");
        stateMachine.transition(job, ExperimentStatus.RUNNING, "running");
        stateMachine.transition(job, ExperimentStatus.VALIDATING_RESULT, "validating");
        stateMachine.transition(job, ExperimentStatus.SUCCEEDED, "succeeded");

        assertEquals(ExperimentStatus.SUCCEEDED, job.getStatus());
    }

    @Test
    void rejectsSkippingValidationAndTerminalTransitions() {
        ExperimentJob job = WavePilotTestFixtures.job("JOB-STATE-2");
        assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(job, ExperimentStatus.RUNNING, "unsafe skip"));

        stateMachine.transition(job, ExperimentStatus.CANCELLED, "cancelled");
        assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(job, ExperimentStatus.RUNNING, "restart terminal job"));
    }
}
