package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentType;

import java.util.List;

/**
 * Type-specific metrics extraction for reports: how accuracy.csv rows and the summary are
 * parsed and cross-checked. ReportDataAssembler dispatches on the job's experiment type.
 * The row fields below are the current report data model; a new experiment type either
 * maps its results into this shape or extends the report model.
 */
public interface ExperimentMetricsExtractor {

    ExperimentType experimentType();

    ExtractedMetrics extract(ArtifactRegistry registry, ExperimentJob job,
                             List<ArtifactRecord> artifacts);

    record ExtractedMetrics(JsonNode summary, List<MetricRow> rows,
                            double minAccuracy, double maxAccuracy, double meanAccuracy,
                            String dimensionColumn, String metricColumn) {

        public ExtractedMetrics {
            // Polar-k extraction carries no CSV column names; declarative extraction does.
            dimensionColumn = dimensionColumn == null ? "codeLength" : dimensionColumn;
            metricColumn = metricColumn == null ? "accuracy" : metricColumn;
        }
    }

    record MetricRow(int row, int codeLength, int trueK, double errorRate, int correctCount,
                     int monteCarloTimes, double accuracy, int sampleCount, long randomSeed,
                     double meanEstimatedK, double mae, double bias, double runtimeSeconds) {
    }
}
