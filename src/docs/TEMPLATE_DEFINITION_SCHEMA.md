# 声明式实验定义 Schema

更新时间：2026-08-06

声明式模板用 `experiment-definition.yaml` 描述完整实验契约。解析是手工逐字段绑定：**未知字段直接拒绝**，避免拼写错误悄悄放宽契约。

## 顶层结构

```yaml
templateId: qpsk-awgn-ber          # [a-z0-9][a-z0-9-]{1,63}
experimentTypeId: qpsk-awgn-ber    # 声明式类型标识（不依赖 Java enum）
displayName: QPSK-AWGN BER 仿真
version: 1.0.0
entryPoint: run_experiment         # 固定入口
description: ...
parameters: [ ... ]                # 参数定义
outputs: { ... }                   # 输出契约
metrics: [ ... ]                   # 报告指标
replay: [ ... ]                    # Replay 比较策略
algorithm: { ... }                 # 算法元数据与验证边界
customExtensionRequired: false     # true = REQUIRES_CUSTOM_EXTENSION
```

## parameters（至少支持）

```yaml
- name: ebNoStart
  type: NUMBER          # STRING | INTEGER | NUMBER | BOOLEAN | ENUM
  required: true
  defaultValue: 0       # 可选
  min: 0                # 可选
  max: 20               # 可选
  minExclusive: false   # 可选
  maxExclusive: false   # 可选
  enumValues: [BPSK, QPSK, 16QAM]   # ENUM 必需
  sweep: false          # true = 网格维度（决定参数点数）
  step: 0.1             # sweep 必需（>0）
  description: ...
  unit: dB
```

## outputs

```yaml
outputs:
  csvFile: accuracy.csv
  requiredColumns: [ebNo, berSim, berTheory]
  numericColumns: [ebNo, berSim, berTheory]   # 必须是 requiredColumns 的子集
  rejectNonFinite: true                        # NaN/Inf 拒绝
  columnBounds:
    berSim: [0, 1]                             # [min, max]，键必须在 requiredColumns
  jsonRequiredFields: [experimentType, algorithmName, rowCount]
  requiredArtifacts: [ACCURACY_CURVE, RUN_LOG] # PNG/MAT/LOG 必需性统一在此声明
```

## metrics

```yaml
metrics:
  - metricName: meanBer
    displayName: 平均 BER
    unit: ratio
    sourceColumn: berSim       # 必须在 requiredColumns
    aggregation: MEAN          # MIN | MAX | MEAN | LATEST
    groupByDimensions: []      # 可选
```

## replay

```yaml
replay:
  - comparisonColumn: berSim
    maxAbsoluteTolerance: 0.001
    meanAbsoluteTolerance: 0.0001
    compareMean: true          # true = 同时报告平均绝对差
    required: true
```

## algorithm

```yaml
algorithm:
  name: demo-ber-awgn-baseline
  version: 0.1.0
  classification: SIMULATION_BASELINE
  algorithmValidated: false    # true 必须带 validationReference（当前发布一律拒绝 true）
  validationReference: null    # 独立人工/科研验证依据
```

## 复杂规则

声明式系统无法表达的规则（非线性约束、跨文件一致性等）必须设置 `customExtensionRequired: true`，模板进入 `REQUIRES_CUSTOM_EXTENSION`，不会假装自动支持。
