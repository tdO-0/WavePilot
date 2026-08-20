package org.example.wavepilot.report;

import org.example.wavepilot.artifact.ArtifactType;

import java.util.List;
import java.util.Map;

public record ExperimentReportData(
        String jobId,
        String experimentType,
        String algorithmName,
        String algorithmVersion,
        String classification,
        boolean mock,
        boolean algorithmValidated,
        List<Integer> codeLengths,
        ErrorRateRange errorRateRange,
        int sampleCount,
        int monteCarloTimes,
        long randomSeed,
        long totalPoints,
        AccuracySummary accuracySummary,
        List<CodeLengthTrend> codeLengthTrends,
        List<AccuracyPoint> accuracyPoints,
        String matlabVersion,
        String runnerType,
        String templateVersion,
        List<ArtifactView> artifacts,
        Map<String, String> configurationCitationIds,
        List<ArtifactCitation> citations,
        List<ReportConclusion> conclusions) {

    public ExperimentReportData {
        codeLengths = List.copyOf(codeLengths);
        codeLengthTrends = List.copyOf(codeLengthTrends);
        accuracyPoints = List.copyOf(accuracyPoints);
        artifacts = List.copyOf(artifacts);
        configurationCitationIds = Map.copyOf(configurationCitationIds);
        citations = List.copyOf(citations);
        conclusions = List.copyOf(conclusions);
    }

    public record ErrorRateRange(double start, double end, double step) { }

    public record AccuracySummary(double minAccuracy, double maxAccuracy, double meanAccuracy,
                                  String minCitationId, String maxCitationId, String meanCitationId) { }

    public record CodeLengthTrend(int codeLength, AccuracyPoint best, AccuracyPoint worst) { }

    public record AccuracyPoint(int rowReference, int codeLength, int trueK, double errorRate,
                                int correctCount, int monteCarloTimes, double accuracy,
                                int sampleCount, long randomSeed, double meanEstimatedK,
                                double mae, double bias, double runtimeSeconds,
                                Map<String, String> citationIds) {
        public AccuracyPoint {
            citationIds = Map.copyOf(citationIds);
        }
    }

    public record ArtifactView(String artifactId, ArtifactType artifactType, String relativePath,
                               String sha256, long size, String mimeType, boolean validated) { }
}
