package org.example.wavepilot.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealPolarAlgorithmTemplateTest {

    @Test
    void templatePreservesSuppliedPolarBscAndBinomialLikelihoodFlow() throws Exception {
        String root = "/matlab/templates/polar-k-identification-simple-v1/";
        String entry = resource(root + "run_experiment.m");
        assertTrue(entry.contains("load_and_validate_spec"));
        assertTrue(entry.contains("run_parameter_sweep"));
        assertTrue(entry.contains("export_results"));
        assertTrue(entry.contains("plot_results"));

        String single = resource(root + "run_single_case.m");
        assertTrue(single.contains("X = mod(U * G, 2)"));
        assertTrue(single.contains("E = rand(sampleCount, N) < epsilon"));
        assertTrue(single.contains("estimate_k_binomial"));

        String estimate = resource(root + "algorithm/estimate_k_binomial.m");
        assertTrue(estimate.contains("Uhat = mod(Y * G, 2)"));
        assertTrue(estimate.contains("zeroCount = sum(Uhat == 0, 1)"));
        assertTrue(estimate.contains("cumsum(delta(reliabilityOrder))"));
        assertFalse(estimate.contains("channelEnergy"));
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
