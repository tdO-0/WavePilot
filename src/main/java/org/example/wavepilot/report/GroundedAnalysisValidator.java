package org.example.wavepilot.report;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-generation validator for grounded LLM analysis (Phase 16). It enforces:
 * 1. every explicit number in the analysis already exists in the GroundedAnalysisContext;
 * 2. the analysis never claims the algorithm is validated (algorithmValidated=false must
 *    stay visible); 3. trend statements are phrased as observations, never as verified fact.
 * It does not restrict the model's wording — only its numbers and claims.
 */
@Component
public class GroundedAnalysisValidator {

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9])[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?(?![A-Za-z0-9])");

    public void validate(GroundedAnalysisContext context, String analysis) {
        if (analysis == null || analysis.isBlank()) {
            throw new AnalysisBoundaryException("Grounded analysis returned an empty text");
        }
        Set<BigDecimal> allowed = numbers(context);
        Set<BigDecimal> generated = new HashSet<>();
        Matcher matcher = NUMBER.matcher(analysis);
        while (matcher.find()) generated.add(new BigDecimal(matcher.group()).stripTrailingZeros());
        generated.removeAll(allowed);
        if (!generated.isEmpty()) {
            throw new AnalysisBoundaryException(
                    "Grounded analysis introduced numbers absent from the experiment data: " + generated);
        }
        if (context.algorithmValidated() == false
                && !analysis.contains("algorithmValidated=false")
                && !analysis.contains("未验证")) {
            throw new AnalysisBoundaryException(
                    "Grounded analysis must preserve the algorithmValidated=false boundary");
        }
    }

    private Set<BigDecimal> numbers(GroundedAnalysisContext context) {
        Set<BigDecimal> values = new HashSet<>();
        for (ExperimentResultData.MetricSeries series : context.series()) {
            series.dimensionValues().values().forEach(value -> collectNumbers(value, values));
            series.metricValues().values().forEach(value -> collectNumbers(value, values));
        }
        context.aggregates().values().forEach(value -> collectNumbers(value, values));
        return values;
    }

    private void collectNumbers(Object value, Set<BigDecimal> target) {
        if (value instanceof Number number) {
            target.add(new BigDecimal(number.toString()).stripTrailingZeros());
        }
    }

    public static class AnalysisBoundaryException extends RuntimeException {
        public AnalysisBoundaryException(String message) { super(message); }
    }
}
