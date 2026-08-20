package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 16 boundary: a grounded LLM analysis may produce new wording but never new numbers.
 * Numbers absent from the validated context are rejected; the algorithmValidated=false
 * boundary must survive.
 */
class GroundedAnalysisBoundaryTest {

    private GroundedAnalysisContext context() {
        return new GroundedAnalysisContext("qpsk-awgn-ber", "qpsk-awgn-ber", "1.0.0",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "ebNoStep", 1, "frames", 200),
                List.of("ebNo"), List.of("berSim", "berTheory"),
                List.of(new ExperimentResultData.MetricSeries(
                        Map.of("ebNo", 0.0), Map.of("berSim", 0.079, "berTheory", 0.079),
                        Map.of("berSim", "CIT-1", "berTheory", "CIT-2"), 1),
                        new ExperimentResultData.MetricSeries(
                                Map.of("ebNo", 2.0), Map.of("berSim", 0.005, "berTheory", 0.006),
                                Map.of("berSim", "CIT-3", "berTheory", "CIT-4"), 2)),
                Map.of("meanBer", 0.042), List.of(), false, false, "SIMULATION_BASELINE");
    }

    @Test
    void analysisUsingOnlyContextNumbersPasses() {
        GroundedAnalysisValidator validator = new GroundedAnalysisValidator();
        String analysis = "随着 Eb/N0 从 0 dB 增至 2 dB，仿真 BER 从 0.079 降至 0.005。"
                + "algorithmValidated=false，趋势仅为观察。";
        assertDoesNotThrow(() -> validator.validate(context(), analysis));
    }

    @Test
    void analysisInventingANumberIsRejected() {
        GroundedAnalysisValidator validator = new GroundedAnalysisValidator();
        String analysis = "在 Eb/N0=5 dB 时 BER 约为 0.0001（该数值不在数据中）。algorithmValidated=false。";
        GroundedAnalysisValidator.AnalysisBoundaryException error = assertThrows(
                GroundedAnalysisValidator.AnalysisBoundaryException.class,
                () -> validator.validate(context(), analysis));
        assertTrue(error.getMessage().contains("absent"),
                "invented numbers must be rejected: " + error.getMessage());
    }

    @Test
    void algorithmValidatedBoundaryMustSurvive() {
        GroundedAnalysisValidator validator = new GroundedAnalysisValidator();
        String analysis = "实验表明该算法 BER 性能优异，仿真 BER 从 0.079 降至 0.005。";
        assertThrows(GroundedAnalysisValidator.AnalysisBoundaryException.class,
                () -> validator.validate(context(), analysis),
                "an analysis claiming validated performance must be rejected");
    }
}
