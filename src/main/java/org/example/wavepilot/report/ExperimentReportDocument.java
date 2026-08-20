package org.example.wavepilot.report;

import java.time.Instant;
import java.util.List;

public record ExperimentReportDocument(
        String jobId,
        CitationStatus status,
        String generatedBy,
        String markdown,
        ExperimentReportData data,
        List<ReportConclusion> conclusions,
        List<ArtifactCitation> citations,
        Instant createdAt) {

    public ExperimentReportDocument {
        conclusions = List.copyOf(conclusions);
        citations = List.copyOf(citations);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
