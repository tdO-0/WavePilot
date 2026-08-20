package org.example.wavepilot.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.GenericExperimentSpec;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declarative-template jobs must carry real generic semantics: no fake polar fields in the
 * spec JSON, and the parameter grid (hence totalRuns) is computed from the actual runtime
 * sweep values (Eb/N0 0:1:10 -> 11 points), not from definition min/max or fake
 * codeLengths.size().
 */
class GenericExperimentSpecTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExperimentDefinitionParser parser = new ExperimentDefinitionParser();

    private ExperimentDefinition qpskDefinition() {
        String yaml = """
                templateId: qpsk-awgn-ber
                experimentTypeId: qpsk-awgn-ber
                displayName: QPSK BER
                version: 1.0.0
                entryPoint: run_experiment
                description: test
                parameters:
                  - name: ebNoStart
                    type: NUMBER
                    required: true
                    defaultValue: 0
                    min: 0
                    max: 20
                    description: start
                    unit: dB
                    sweep: true
                    step: 1
                  - name: ebNoEnd
                    type: NUMBER
                    required: true
                    defaultValue: 10
                    min: 0
                    max: 20
                    description: end
                    unit: dB
                    sweep: true
                  - name: ebNoStep
                    type: NUMBER
                    required: true
                    defaultValue: 1
                    min: 0.1
                    max: 2
                    description: step
                    unit: dB
                    sweep: true
                  - name: frames
                    type: INTEGER
                    required: true
                    defaultValue: 200
                    min: 10
                    max: 100000
                    description: frames
                outputs:
                  csvFile: accuracy.csv
                  requiredColumns: [ebNo, berSim, berTheory]
                  numericColumns: [ebNo, berSim, berTheory]
                  rejectNonFinite: true
                  jsonRequiredFields: [experimentType, algorithmName, rowCount]
                  requiredArtifacts: [RUN_LOG]
                metrics:
                  - metricName: meanBer
                    displayName: mean BER
                    unit: ratio
                    sourceColumn: berSim
                    aggregation: MEAN
                replay:
                  - comparisonColumn: berSim
                    maxAbsoluteTolerance: 0.001
                    meanAbsoluteTolerance: 0.0001
                    compareMean: true
                    required: true
                algorithm:
                  name: qpsk-baseline
                  version: 0.1.0
                  classification: SIMULATION_BASELINE
                  algorithmValidated: false
                """;
        return parser.parse(yaml);
    }

    @Test
    void genericSpecJsonHasNoFakePolarFields() throws Exception {
        GenericExperimentSpec spec = GenericExperimentSpec.of("qpsk-awgn-ber", "qpsk-awgn-ber",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "ebNoStep", 1, "frames", 200));
        String json = mapper.writeValueAsString(spec);
        assertFalse(json.contains("POLAR_CODE_K_IDENTIFICATION"),
                "declarative jobs must never carry POLAR_CODE_K_IDENTIFICATION");
        assertFalse(json.contains("codeLengths"),
                "declarative jobs must never carry fake codeLengths");
        assertFalse(json.contains("errorRateStart"),
                "declarative jobs must never carry fake BSC error-rate fields");
        assertTrue(json.contains("experimentTypeId"), "the template type must be present");
        assertTrue(json.contains("parameters"), "runtime parameters must be a map");
    }

    @Test
    void gridResolverComputesElevenPointsFromTheRuntimeSweep() {
        ExperimentGridResolver resolver = new ExperimentGridResolver();
        GenericExperimentSpec spec = GenericExperimentSpec.of("qpsk-awgn-ber", "qpsk-awgn-ber",
                Map.of("ebNoStart", 0, "ebNoEnd", 10, "ebNoStep", 1, "frames", 200));
        ExperimentGridResolver.Grid grid = resolver.resolve(qpskDefinition(), spec);
        assertEquals(11, grid.totalPoints(), "Eb/N0=0:1:10 must yield 11 parameter points");
        assertEquals(11L, grid.dimensionSizes().get("ebNo"));
    }

    @Test
    void gridResolverUsesRuntimeValuesNotDefinitionDefaults() {
        ExperimentGridResolver resolver = new ExperimentGridResolver();
        GenericExperimentSpec spec = GenericExperimentSpec.of("qpsk-awgn-ber", "qpsk-awgn-ber",
                Map.of("ebNoStart", 0, "ebNoEnd", 4, "ebNoStep", 2, "frames", 200));
        ExperimentGridResolver.Grid grid = resolver.resolve(qpskDefinition(), spec);
        assertEquals(3, grid.totalPoints(), "Eb/N0=0:2:4 must yield 3 points, not definition defaults");
    }

    @Test
    void ofFactoryCarriesTemplateIdentity() {
        GenericExperimentSpec spec = GenericExperimentSpec.of("ofdm-cp-study", "ofdm-cp-study",
                Map.of("fftSize", 64, "cpLengths", "16,32", "snrStart", 0, "snrEnd", 10, "snrStep", 2));
        assertEquals("ofdm-cp-study", spec.templateId());
        assertEquals("ofdm-cp-study", spec.experimentTypeId());
        assertEquals("16,32", spec.parameter("cpLengths"));
        assertTrue(spec.requestedArtifacts().contains("ACCURACY_CSV"));
    }
}
