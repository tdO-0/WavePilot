package org.example.wavepilot.report;

import java.util.List;

/** Receives structured report data only; it has no tool, repository, runner or filesystem handle. */
public interface ReportLanguageModel {
    ReportAgentDraft rewrite(ExperimentReportData data, String templateMarkdown);

    record ReportAgentDraft(String markdown, List<ReportConclusion> conclusions) {
        public ReportAgentDraft {
            conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        }
    }
}
