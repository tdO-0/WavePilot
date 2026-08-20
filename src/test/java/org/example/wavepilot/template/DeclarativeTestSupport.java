package org.example.wavepilot.template;

import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;

import java.util.List;

/**
 * Shared fixture for the declarative-template tests: a demo BER-AWGN definition that is
 * deliberately NOT a real scientific algorithm (SIMULATION_BASELINE, algorithmValidated=false).
 */
public final class DeclarativeTestSupport {

    public static final String DEMO_TYPE_ID = "demo-ber-awgn";
    public static final String DEMO_TEMPLATE_ID = "demo-ber-awgn";

    public static final String DEMO_DEFINITION_YAML = """
            templateId: demo-ber-awgn
            experimentTypeId: demo-ber-awgn
            displayName: 演示 BER-AWGN 仿真
            version: 1.0.0
            entryPoint: run_experiment
            description: 演示用声明式 BER 模板（不是真实科学算法）
            parameters:
              - name: ebNoStart
                type: NUMBER
                required: true
                min: 0
                max: 20
                description: Eb/N0 起始值
                unit: dB
              - name: ebNoEnd
                type: NUMBER
                required: true
                min: 0
                max: 20
                description: Eb/N0 结束值
                unit: dB
              - name: ebNoStep
                type: NUMBER
                required: true
                min: 0.1
                max: 2
                description: Eb/N0 步长
                unit: dB
              - name: sweepEbNo
                type: NUMBER
                required: false
                defaultValue: 10
                min: 0
                max: 20
                step: 1
                sweep: true
                description: 网格维度（演示）
                unit: dB
              - name: frames
                type: INTEGER
                required: true
                min: 10
                max: 100000
                description: 仿真帧数
              - name: modulation
                type: ENUM
                required: false
                defaultValue: QPSK
                enumValues: [BPSK, QPSK, 16QAM]
                description: 调制方式
            outputs:
              csvFile: accuracy.csv
              requiredColumns: [ebNo, berSim, berTheory]
              numericColumns: [ebNo, berSim, berTheory]
              rejectNonFinite: true
              columnBounds:
                berSim: [0, 1]
              jsonRequiredFields: [experimentType, algorithmName, rowCount]
              requiredArtifacts: [ACCURACY_CURVE, RUN_LOG]
            metrics:
              - metricName: meanBer
                displayName: 平均 BER
                unit: ratio
                sourceColumn: berSim
                aggregation: MEAN
              - metricName: minBer
                displayName: 最小 BER
                unit: ratio
                sourceColumn: berSim
                aggregation: MIN
            replay:
              - comparisonColumn: berSim
                maxAbsoluteTolerance: 0.001
                meanAbsoluteTolerance: 0.0001
                compareMean: true
                required: true
              - comparisonColumn: berTheory
                maxAbsoluteTolerance: 0.001
                compareMean: false
                required: true
            algorithm:
              name: demo-ber-awgn-baseline
              version: 0.1.0
              classification: SIMULATION_BASELINE
              algorithmValidated: false
            """;

    private DeclarativeTestSupport() { }

    public static ExperimentDefinition demoDefinition() {
        ExperimentDefinition definition = new ExperimentDefinitionParser().parse(DEMO_DEFINITION_YAML);
        List<String> errors = new ExperimentDefinitionValidator().validate(definition);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Demo definition must be valid: " + errors);
        }
        return definition;
    }

    public static ExperimentDefinitionRegistry registryWithDemo() {
        ExperimentDefinitionRegistry registry = new ExperimentDefinitionRegistry();
        registry.register(demoDefinition());
        return registry;
    }
}
