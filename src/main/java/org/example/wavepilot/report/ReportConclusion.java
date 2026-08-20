package org.example.wavepilot.report;

import java.util.List;

public record ReportConclusion(
        String conclusionId,
        String text,
        String metricName,
        Number metricValue,
        List<String> citationIds,
        CitationStatus citationStatus) {

    public ReportConclusion {
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        citationStatus = citationStatus == null ? CitationStatus.UNVERIFIED : citationStatus;
    }
}
