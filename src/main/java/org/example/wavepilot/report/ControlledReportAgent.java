package org.example.wavepilot.report;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ControlledReportAgent {

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9])[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?(?![A-Za-z0-9])");

    public ReportLanguageModel.ReportAgentDraft rewrite(ReportLanguageModel model,
                                                         ExperimentReportData data,
                                                         ExperimentReportDocument template) {
        ReportLanguageModel.ReportAgentDraft draft = model.rewrite(data, template.markdown());
        if (draft == null || draft.markdown() == null || draft.markdown().isBlank())
            throw new ReportAgentBoundaryException("Report model returned an empty report");
        if (!draft.conclusions().equals(data.conclusions()))
            throw new ReportAgentBoundaryException("Report model changed metric values or citations");
        for (String required : List.of("SIMPLIFIED_BASELINE", "algorithmValidated=false",
                "mock=false", "不能作为论文复现结果")) {
            if (!draft.markdown().contains(required))
                throw new ReportAgentBoundaryException("Report model removed required boundary: " + required);
        }
        Set<BigDecimal> allowed = numbers(template.markdown());
        Set<BigDecimal> generated = numbers(draft.markdown());
        if (!allowed.containsAll(generated))
            throw new ReportAgentBoundaryException("Report model introduced a numeric value absent from ExperimentReportData");
        return draft;
    }

    private Set<BigDecimal> numbers(String text) {
        Set<BigDecimal> values = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) values.add(new BigDecimal(matcher.group()).stripTrailingZeros());
        return values;
    }

    public static class ReportAgentBoundaryException extends RuntimeException {
        public ReportAgentBoundaryException(String message) { super(message); }
    }
}
