package org.example.wavepilot.replay;

import java.util.List;

/**
 * Structured comparison of a source experiment and its replay. Strict axes (spec, seed,
 * runner, template, algorithm version, CSV row count, parameter grid) are boolean checks;
 * numeric metrics (accuracy/MAE/bias) are compared per parameter point and gated by the
 * configured numeric tolerance. MAE/bias are only comparable when both CSVs carry the
 * column (the 3-column mock contract has neither).
 */
public record ReplayComparisonResult(
        String replayId,
        String sourceJobId,
        String replayJobId,
        boolean specConsistent,
        boolean randomSeedConsistent,
        boolean runnerTypeConsistent,
        boolean templateVersionConsistent,
        boolean algorithmVersionConsistent,
        long sourceCsvRows,
        long replayCsvRows,
        boolean csvRowCountConsistent,
        boolean parameterGridConsistent,
        List<MetricComparison> metrics,
        boolean withinTolerance,
        boolean consistent,
        String verdict,
        String message) {

    public static final String REPRODUCIBLE = "REPRODUCIBLE";
    public static final String NOT_REPRODUCIBLE = "NOT_REPRODUCIBLE";

    public ReplayComparisonResult {
        metrics = List.copyOf(metrics);
    }

    public record MetricComparison(String metricName, boolean present,
                                   Double sourceValue, Double replayValue,
                                   Double maxAbsDifference, Double meanAbsDifference,
                                   boolean withinTolerance) {
    }
}
