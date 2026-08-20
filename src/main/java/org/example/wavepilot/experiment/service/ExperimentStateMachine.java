package org.example.wavepilot.experiment.service;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class ExperimentStateMachine {

    private final Map<ExperimentStatus, Set<ExperimentStatus>> transitions = new EnumMap<>(ExperimentStatus.class);

    public ExperimentStateMachine() {
        transitions.put(ExperimentStatus.CREATED, EnumSet.of(ExperimentStatus.VALIDATED,
                ExperimentStatus.FAILED, ExperimentStatus.CANCELLED));
        transitions.put(ExperimentStatus.VALIDATED, EnumSet.of(ExperimentStatus.QUEUED,
                ExperimentStatus.FAILED, ExperimentStatus.CANCELLED));
        transitions.put(ExperimentStatus.QUEUED, EnumSet.of(ExperimentStatus.RUNNING,
                ExperimentStatus.FAILED, ExperimentStatus.CANCELLED));
        transitions.put(ExperimentStatus.RUNNING, EnumSet.of(ExperimentStatus.VALIDATING_RESULT,
                ExperimentStatus.FAILED, ExperimentStatus.CANCELLED));
        transitions.put(ExperimentStatus.VALIDATING_RESULT, EnumSet.of(ExperimentStatus.SUCCEEDED,
                ExperimentStatus.FAILED, ExperimentStatus.CANCELLED));
        transitions.put(ExperimentStatus.SUCCEEDED, EnumSet.noneOf(ExperimentStatus.class));
        transitions.put(ExperimentStatus.FAILED, EnumSet.noneOf(ExperimentStatus.class));
        transitions.put(ExperimentStatus.CANCELLED, EnumSet.noneOf(ExperimentStatus.class));
    }

    public void transition(ExperimentJob job, ExperimentStatus next, String message) {
        synchronized (job) {
            ExperimentStatus current = job.getStatus();
            if (!transitions.getOrDefault(current, Set.of()).contains(next)) {
                throw new IllegalStateException("Illegal experiment state transition: " + current + " -> " + next);
            }
            job.changeStatus(next, message);
        }
    }

    public boolean canTransition(ExperimentStatus current, ExperimentStatus next) {
        return transitions.getOrDefault(current, Set.of()).contains(next);
    }
}
