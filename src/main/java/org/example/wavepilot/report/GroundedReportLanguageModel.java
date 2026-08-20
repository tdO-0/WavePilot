package org.example.wavepilot.report;

/**
 * Grounded LLM analysis for generic (declarative) experiments. The model receives only the
 * {@link GroundedAnalysisContext} — validated data with citations — and produces a natural
 * language analysis. It can reason about trends, turning points, anomalies, theory-vs-
 * simulation gaps and next steps, but any explicit number it states must already exist in
 * the context, and every numeric claim must carry a citation.
 */
public interface GroundedReportLanguageModel {

    String name();

    AnalysisDraft analyze(GroundedAnalysisContext context);

    record AnalysisDraft(String analysisMarkdown, java.util.List<ReportConclusion> conclusions) {
        public AnalysisDraft {
            conclusions = conclusions == null ? java.util.List.of() : java.util.List.copyOf(conclusions);
        }
    }
}
