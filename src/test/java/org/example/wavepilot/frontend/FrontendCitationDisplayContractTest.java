package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Citations must be shown as integrity states with locator info, never as probabilities. */
class FrontendCitationDisplayContractTest {

    @Test
    void citationsAreRenderedWithStatusLabelAndLocator() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("引用状态"), "citation status label missing");
        assertTrue(app.contains("VERIFIED"), "VERIFIED state missing");
        assertTrue(app.contains("UNVERIFIED"), "UNVERIFIED state missing");
        assertTrue(app.contains("citation.artifactId"), "citation must carry its artifact locator");
        assertTrue(app.contains("定位 Artifact"), "citation locator button missing");
    }

    @Test
    void citationStatusIsNeverFormattedAsAPercentageOrProbability() {
        String app = FrontendTestSupport.appJs();
        assertFalse(anyLineCombines(app, "citationStatus", ".toFixed"),
                "citation status must not be formatted numerically on the same line");
        assertFalse(anyLineCombines(app, "引用状态", "* 100"),
                "citation status must never be scaled to a probability on the same line");
    }

    @Test
    void similarityAndReplayVerdictsAreAlsoNotProbabilities() {
        String app = FrontendTestSupport.appJs();
        assertFalse(anyLineCombines(app, "similarityScore", ".toFixed"),
                "similarityScore must not be displayed as a probability");
        assertTrue(app.contains("comparison.verdict"), "replay verdict must be displayed");
        assertFalse(anyLineCombines(app, "verdict", "probability"),
                "replay verdict must not be described as a probability");
    }

    private boolean anyLineCombines(String source, String first, String second) {
        return java.util.Arrays.stream(source.split("\n"))
                .anyMatch(line -> line.contains(first) && line.contains(second));
    }

    @Test
    void citationListHasADedicatedUiSection() {
        String html = FrontendTestSupport.indexHtml();
        assertTrue(html.contains("id=\"citationList\""), "citation list section missing");
        assertTrue(html.contains("id=\"reportContent\""), "report section missing");
    }

    @Test
    void reportSummaryStateTracksGenerationAndCitationValidation() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("resultReportState"), "report summary state must be bound");
        assertTrue(app.contains("已生成 · 引用已验证"),
                "a generated and verified report must update its summary state");
        assertTrue(app.contains("引用校验失败"),
                "a failed citation validation must be surfaced in the summary state");
    }
}
