package org.example.wavepilot.replay;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.ReplayMetricDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Replay comparison metrics driven by a declarative definition's replay section: each
 * declared comparisonColumn becomes a compared metric with its tolerances, and compareMean
 * switches on the mean-absolute-difference reporting.
 */
public class DeclarativeComparisonMetrics implements ExperimentComparisonMetrics {

    private final ExperimentDefinitionRegistry definitionRegistry;
    private final String experimentTypeId;

    public DeclarativeComparisonMetrics(ExperimentDefinitionRegistry definitionRegistry,
                                        String experimentTypeId) {
        this.definitionRegistry = definitionRegistry;
        this.experimentTypeId = experimentTypeId;
    }

    @Override
    public ExperimentType experimentType() {
        return null;
    }

    @Override
    public String experimentTypeId() {
        return experimentTypeId;
    }

    @Override
    public List<String> gridColumns() {
        return List.of();
    }

    @Override
    public List<Metric> metrics() {
        ExperimentDefinition definition = definitionRegistry
                .byExperimentTypeId(experimentTypeId)
                .orElseThrow(() -> new ReplayComparisonEvaluator.ReplayComparisonException(
                        "No declarative definition for experimentTypeId: " + experimentTypeId));
        List<Metric> metrics = new ArrayList<>();
        for (ReplayMetricDefinition replay : definition.replay()) {
            metrics.add(new Metric(replay.comparisonColumn(), replay.compareMean()));
        }
        return metrics;
    }
}
