package org.example.wavepilot.report;

import org.example.wavepilot.experiment.model.GenericExperimentSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A generic QPSK BER report must speak QPSK/AWGN/Eb-N0/BER and never contain polar
 * language (极化码/码维数/BSC/codeLength/meanEstimatedK). The report text is driven by the
 * template semantics via ExperimentResultData, not by a polar template.
 */
class GenericReportSemanticsTest {

    private ExperimentResultData qpskResult() {
        return new ExperimentResultData("JOB-QPSK-1", "qpsk-awgn-ber", "qpsk-awgn-ber", "1.0.0",
                "qpsk-baseline", "0.1.0", "SIMULATION_BASELINE", false, false,
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "ebNoStep", 1, "frames", 200),
                List.of("ebNo"), List.of("berSim", "berTheory"),
                List.of(new ExperimentResultData.MetricSeries(
                        Map.of("ebNo", 0.0), Map.of("berSim", 0.079, "berTheory", 0.079),
                        Map.of("berSim", "CIT-1", "berTheory", "CIT-2"), 1)),
                Map.of("meanBer", 0.079), "mock", "R2023b",
                List.of(), List.of(), List.of());
    }

    private ExperimentResultData becResult() {
        return new ExperimentResultData("JOB-BEC-1", "bec-ber", "bec-ber", "1.0.0",
                "bec-baseline", "0.1.0", "SIMULATION_BASELINE", false, false,
                Map.of("erasureStart", 0, "erasureEnd", 0.5, "erasureStep", 0.1, "frames", 200),
                List.of("erasureProb"), List.of("berSim", "berTheory"),
                List.of(new ExperimentResultData.MetricSeries(
                        Map.of("erasureProb", 0.1), Map.of("berSim", 0.05, "berTheory", 0.05),
                        Map.of("berSim", "CIT-1"), 1)),
                Map.of("meanBer", 0.05), "mock", "R2023b",
                List.of(), List.of(), List.of());
    }

    @Test
    void qpskReportSpeaksQpskBerAndNeverPolar() {
        GenericExperimentReportGenerator generator = new GenericExperimentReportGenerator();
        String markdown = generator.markdown(qpskResult());
        assertTrue(markdown.contains("QPSK"), "QPSK report must mention QPSK");
        assertTrue(markdown.contains("AWGN"), "QPSK report must mention AWGN");
        assertTrue(markdown.contains("ebNo"), "QPSK report must use the Eb/N0 dimension");
        assertTrue(markdown.contains("berSim"), "QPSK report must use the BER metric");
        assertFalse(markdown.contains("极化码"), "QPSK report must not mention 极化码");
        assertFalse(markdown.contains("码维数"), "QPSK report must not mention 码维数");
        assertFalse(markdown.contains("BSC"), "QPSK report must not mention BSC");
        assertFalse(markdown.contains("codeLength"), "QPSK report must not mention codeLength");
        assertFalse(markdown.contains("meanEstimatedK"), "QPSK report must not mention meanEstimatedK");
        assertFalse(markdown.contains("识别准确率"), "QPSK report must not speak polar accuracy");
    }

    @Test
    void becReportSpeaksErasureProbability() {
        GenericExperimentReportGenerator generator = new GenericExperimentReportGenerator();
        String markdown = generator.markdown(becResult());
        assertTrue(markdown.contains("erasureProb"), "BEC report must use the erasure dimension");
        assertTrue(markdown.contains("删除概率"), "BEC report must explain the erasure study");
        assertFalse(markdown.contains("极化码"), "BEC report must not mention polar");
    }

    @Test
    void genericSpecNeverCarriesPolarFields() {
        GenericExperimentSpec spec = GenericExperimentSpec.of("qpsk-awgn-ber", "qpsk-awgn-ber",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "ebNoStep", 1, "frames", 200));
        assertFalse(spec.toString().contains("POLAR_CODE_K_IDENTIFICATION"));
        assertFalse(spec.toString().contains("codeLengths"));
    }
}
