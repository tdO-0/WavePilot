package org.example.wavepilot.template.generation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic offline generation model used only in test/offline mode
 * ({@code wavepilot.template-generation.mode=stub}, the default). It produces fixed demo
 * candidate packages selected by keywords in the request (AWGN QPSK/BPSK BER, BEC BER);
 * it is a test double, never a real scientific template generator. Templates that do not
 * match any built-in variant still land in a safe AWGN demo package.
 */
@Component
@ConditionalOnProperty(prefix = "wavepilot", name = "template-generation.mode",
        havingValue = "stub", matchIfMissing = true)
public class StubTemplateGenerationModel implements TemplateGenerationModel {

    public static final String NAME = "stub-template-gen";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TemplateGenerationResult generate(String request) {
        return generate(request, null);
    }

    @Override
    public TemplateGenerationResult generate(ExperimentTemplateDesignRequest request) {
        return generate(request.userRequest(), request.intent());
    }

    /** Shared generation; the intent (when present) drives the variant selection. */
    private TemplateGenerationResult generate(String request, org.example.wavepilot.intent.ExperimentIntent intent) {
        String id = idFrom(request, intent);
        boolean bec = id.startsWith("bec-");
        String yaml = definitionYaml(id, bec);
        String manifest = manifestJson(id);
        return new TemplateGenerationResult(
                id,
                id,
                displayName(id),
                "1.0.0",
                "演示用声明式 " + (bec ? "BEC" : "AWGN") + " 模板（不是真实科学算法）",
                yaml,
                manifest,
                List.of(
                        new GeneratedFile("matlab/run_experiment.m", runExperimentScript(id)),
                        new GeneratedFile("README.md", readme(id))),
                "Stub 模型按固定模板生成；真实模型生成属于 external-template-generation profile",
                bec
                        ? List.of("删除概率 ε 范围与步长由参数决定", "BER 统计为演示数值")
                        : List.of("Eb/N0 范围与步长由参数决定", "BER 统计为演示数值"),
                List.of("是否需要在真实 MATLAB 上运行 Smoke？"));
    }

    private String idFrom(String request, org.example.wavepilot.intent.ExperimentIntent intent) {
        String lower = request == null ? "" : request.toLowerCase();
        if (intent != null) {
            if ("bec".equalsIgnoreCase(intent.coding()) || "bec".equalsIgnoreCase(intent.channel())
                    || intent.semanticTags().stream().anyMatch(t -> t.toLowerCase().contains("bec"))) {
                return "bec-ber";
            }
            if ("qpsk".equalsIgnoreCase(intent.modulation())
                    || intent.semanticTags().stream().anyMatch(t -> t.toLowerCase().contains("qpsk"))) {
                return "qpsk-awgn-ber";
            }
            if ("bpsk".equalsIgnoreCase(intent.modulation())
                    || intent.semanticTags().stream().anyMatch(t -> t.toLowerCase().contains("bpsk"))) {
                return "bpsk-awgn-ber";
            }
        }
        if (lower.contains("bec") || lower.contains("删除信道") || lower.contains("擦除信道")) {
            return "bec-ber";
        }
        if (lower.contains("qpsk")) return "qpsk-awgn-ber";
        if (lower.contains("bpsk")) return "bpsk-awgn-ber";
        return "demo-ber-awgn";
    }

    private String displayName(String id) {
        return switch (id) {
            case "bec-ber" -> "演示 BEC 删除信道 BER 仿真";
            case "bpsk-awgn-ber" -> "演示 BPSK-AWGN BER 仿真";
            default -> "演示 qpsk-awgn-ber 仿真";
        };
    }

    private String definitionYaml(String id, boolean bec) {
        if (bec) {
            return """
                    templateId: %s
                    experimentTypeId: %s
                    displayName: 演示 BEC 删除信道 BER 仿真
                    version: 1.0.0
                    entryPoint: run_experiment
                    description: 演示用声明式 BEC 模板（不是真实科学算法）
                    parameters:
                      - name: erasureStart
                        type: NUMBER
                        required: true
                        defaultValue: 0
                        min: 0
                        max: 0.9
                        description: 删除概率 ε 起始值
                        unit: ratio
                      - name: erasureEnd
                        type: NUMBER
                        required: true
                        defaultValue: 0.5
                        min: 0
                        max: 0.9
                        description: 删除概率 ε 结束值
                        unit: ratio
                      - name: erasureStep
                        type: NUMBER
                        required: true
                        defaultValue: 0.1
                        min: 0.01
                        max: 0.5
                        description: 删除概率 ε 步长
                        unit: ratio
                      - name: frames
                        type: INTEGER
                        required: true
                        min: 10
                        max: 100000
                        description: 仿真帧数
                    outputs:
                      csvFile: accuracy.csv
                      requiredColumns: [erasureProb, berSim, berTheory]
                      numericColumns: [erasureProb, berSim, berTheory]
                      rejectNonFinite: true
                      columnBounds:
                        berSim: [0, 1]
                      jsonRequiredFields: [experimentType, algorithmName, rowCount]
                      requiredArtifacts: [RUN_LOG]
                    metrics:
                      - metricName: meanBer
                        displayName: 平均 BER
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
                      name: %s-bec-baseline
                      version: 0.1.0
                      classification: SIMULATION_BASELINE
                      algorithmValidated: false
                    """.formatted(id, id, id);
        }
        return awgnDefinitionYaml(id);
    }

    private String awgnDefinitionYaml(String id) {
        return """
                templateId: %s
                experimentTypeId: %s
                displayName: 演示 %s 仿真
                version: 1.0.0
                entryPoint: run_experiment
                description: 演示用声明式 BER 模板（不是真实科学算法）
                parameters:
                  - name: ebNoStart
                    type: NUMBER
                    required: true
                    defaultValue: 0
                    min: 0
                    max: 20
                    description: Eb/N0 起始值
                    unit: dB
                  - name: ebNoEnd
                    type: NUMBER
                    required: true
                    defaultValue: 4
                    min: 0
                    max: 20
                    description: Eb/N0 结束值
                    unit: dB
                  - name: ebNoStep
                    type: NUMBER
                    required: true
                    defaultValue: 0.5
                    min: 0.1
                    max: 2
                    description: Eb/N0 步长
                    unit: dB
                  - name: frames
                    type: INTEGER
                    required: true
                    defaultValue: 200
                    min: 10
                    max: 100000
                    description: 仿真帧数
                outputs:
                  csvFile: accuracy.csv
                  requiredColumns: [ebNo, berSim, berTheory]
                  numericColumns: [ebNo, berSim, berTheory]
                  rejectNonFinite: true
                  columnBounds:
                    berSim: [0, 1]
                  jsonRequiredFields: [experimentType, algorithmName, rowCount]
                  requiredArtifacts: [RUN_LOG]
                metrics:
                  - metricName: meanBer
                    displayName: 平均 BER
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
                  name: %s-baseline
                  version: 0.1.0
                  classification: SIMULATION_BASELINE
                  algorithmValidated: false
                """.formatted(id, id, id, id);
    }

    private String manifestJson(String id) {
        return """
                {
                  "schemaVersion": 1,
                  "templateName": "%s",
                  "templateVersion": "1.0.0",
                  "entryPoint": "run_experiment",
                  "experimentType": "%s",
                  "algorithmName": "%s-baseline",
                  "algorithmVersion": "0.1.0",
                  "algorithmValidated": false,
                  "classification": "SIMULATION_BASELINE"
                }
                """.formatted(id, id, id);
    }

    private String runExperimentScript(String id) {
        return id.startsWith("bec-") ? becScript(id) : awgnScript(id);
    }

    /**
     * Minimal demo execution script: it really produces accuracy.csv, summary.json and
     * run.log with fixed demo values. It is NOT a scientific algorithm
     * (SIMULATION_BASELINE, algorithmValidated=false) and contains no dangerous calls.
     */
    private String awgnScript(String id) {
        return """
                %% __ID__ demo template (stub-generated, not a real scientific algorithm)
                function run_experiment(inputFile, outputDir)
                  data = jsondecode(fileread(inputFile));
                  p = data.customParameters;
                  start = p.ebNoStart; stop = p.ebNoEnd; step = p.ebNoStep;
                  ebNos = start:step:stop;
                  fid = fopen(fullfile(outputDir, 'accuracy.csv'), 'w');
                  fprintf(fid, 'ebNo,berSim,berTheory\\n');
                  rowCount = 0; sumBer = 0; minBer = 1; maxBer = 0;
                  for eb = ebNos
                    berTheory = 0.5 * erfc(sqrt(10^(eb/10)/2));
                    berSim = min(1, max(0, berTheory + 0.01 * sin(eb)));
                    fprintf(fid, '%.6f,%.10f,%.10f\\n', eb, berSim, berTheory);
                    rowCount = rowCount + 1; sumBer = sumBer + berSim;
                    minBer = min(minBer, berSim); maxBer = max(maxBer, berSim);
                  end
                  fclose(fid);
                  summary = struct();
                  summary.experimentType = '__ID__';
                  summary.algorithmName = '__ID__-baseline';
                  summary.rowCount = rowCount;
                  summary.minAccuracy = minBer;
                  summary.maxAccuracy = maxBer;
                  summary.meanAccuracy = sumBer / max(1, rowCount);
                  summary.mock = false;
                  summary.algorithmValidated = false;
                  summary.classification = 'SIMULATION_BASELINE';
                  fid = fopen(fullfile(outputDir, 'summary.json'), 'w');
                  fprintf(fid, '%s', jsonencode(summary));
                  fclose(fid);
                  fid = fopen(fullfile(outputDir, 'run.log'), 'w');
                  fprintf(fid, 'demo BER run: %d points\\n', rowCount);
                  fclose(fid);
                end
                """.replace("__ID__", id);
    }

    private String becScript(String id) {
        return """
                %% __ID__ demo template (stub-generated, not a real scientific algorithm)
                function run_experiment(inputFile, outputDir)
                  data = jsondecode(fileread(inputFile));
                  p = data.customParameters;
                  start = p.erasureStart; stop = p.erasureEnd; step = p.erasureStep;
                  epsilons = start:step:stop;
                  fid = fopen(fullfile(outputDir, 'accuracy.csv'), 'w');
                  fprintf(fid, 'erasureProb,berSim,berTheory\\n');
                  rowCount = 0; sumBer = 0; minBer = 1; maxBer = 0;
                  for eps = epsilons
                    berSim = min(1, max(0, eps * 0.5));
                    berTheory = eps * 0.5;
                    fprintf(fid, '%.6f,%.10f,%.10f\\n', eps, berSim, berTheory);
                    rowCount = rowCount + 1; sumBer = sumBer + berSim;
                    minBer = min(minBer, berSim); maxBer = max(maxBer, berSim);
                  end
                  fclose(fid);
                  summary = struct();
                  summary.experimentType = '__ID__';
                  summary.algorithmName = '__ID__-bec-baseline';
                  summary.rowCount = rowCount;
                  summary.minAccuracy = minBer;
                  summary.maxAccuracy = maxBer;
                  summary.meanAccuracy = sumBer / max(1, rowCount);
                  summary.mock = false;
                  summary.algorithmValidated = false;
                  summary.classification = 'SIMULATION_BASELINE';
                  fid = fopen(fullfile(outputDir, 'summary.json'), 'w');
                  fprintf(fid, '%s', jsonencode(summary));
                  fclose(fid);
                  fid = fopen(fullfile(outputDir, 'run.log'), 'w');
                  fprintf(fid, 'demo BEC run: %d points\\n', rowCount);
                  fclose(fid);
                end
                """.replace("__ID__", id);
    }

    private String readme(String id) {
        return "# " + id + "\n\n演示用候选模板包（Stub 生成）。\n"
                + "- 不是真实科学算法\n- algorithmValidated=false\n";
    }
}
