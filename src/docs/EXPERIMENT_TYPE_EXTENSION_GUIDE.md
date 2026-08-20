# 新增实验类型扩展指南

更新时间：2026-08-06

WavePilot 把"一个实验类型"定义为一套**代码级注册的完整契约**，不是运行时插件。接入新实验类型有三种方式（见下），全部是显式注册，不做反射、动态 JAR、任意脚本上传，也不引入新 Agent 框架。

## 三种扩展方式（选一）

### A. 声明式模板（推荐，无需改 Java 源码）

适用于参数扫描、BER、SNR 扫描、简单通信仿真。写一个 `experiment-definition.yaml`（见 TEMPLATE_DEFINITION_SCHEMA.md），通过候选模板流程发布为 ACTIVE 后，`ExperimentSpecValidator`/`ResultValidator`/`ReportDataAssembler`/`ReplayComparisonEvaluator` 的声明式实现自动接管——**不修改任何 Java Map**。

### B. 内置专用模板（复杂业务）

适用于极化码盲识别等复杂规则。实现 6 块 Java 契约（ExperimentTypeValidator / ExperimentResultContractValidator / ExperimentMetricsExtractor / ExperimentComparisonMetrics / MatlabTemplateCatalog 注册 / 报告适配），参考现有 `PolarCodeK*` 实现与本指南下文。

### C. Agent 生成候选模板（一句话起手式）

Agent 负责生成候选包，系统负责安全校验，**用户负责审批发布**（见 TEMPLATE_GENERATION_AND_PUBLISHING.md）。一句话添加的是 Candidate，不是立即生效的正式模板；ACTIVE 模板才能执行。

## 选择规则

- 声明式系统无法表达的复杂规则 → 定义里 `customExtensionRequired: true` → `REQUIRES_CUSTOM_EXTENSION`，绝不假装自动支持。
- 一句话添加的是 Candidate，不是立即生效的正式模板；
- ACTIVE 模板才能执行；
- operationalValidated 与 algorithmValidated 不同（能跑 ≠ 算法已验证）；
- 模板可运行不等于论文复现正确；
- 系统不支持任意 MATLAB/Shell 执行。

---

## 接入新类型（B 方式）的 7 块工作

## 总体原则

- **注册表内聚在组件构造内**：`ExperimentSpecValidator`、`ResultValidator`、`ReportDataAssembler`、`ReplayComparisonEvaluator` 各自持有类型→实现的显式 Map，新增类型就是往这些 Map 里加一行 + 实现一个接口。
- **未注册类型被明确拒绝**：校验、执行、报告、Replay 四道关口都会报 `Unsupported experiment type: X; registered: [...]`，不会静默放行。
- **契约绑定模板版本**：真实 MATLAB 结果契约绑定在版本化模板上（`ResultValidator` 的 `contractByTemplate`），Mock/集成 fixture 模板走通用 3 列契约。

## 接入新类型的 7 块工作

### 1. 实验类型枚举

`src/main/java/org/example/wavepilot/experiment/model/ExperimentType.java` 增加枚举值（如 `QPSK_AWGN_BER`）。

### 2. Spec 参数校验器

- 实现 `org.example.wavepilot.experiment.validation.ExperimentTypeValidator`：
  - `experimentType()` 返回新类型；
  - `validate(ExperimentSpec)` 定义该类型的参数语义（范围、取值、资源风险警告）；
  - `pointCount(ExperimentSpec)` 定义参数网格点数（Runner 与校验共用）。
- 参考实现：`PolarCodeKTypeValidator`。
- 注册：在 `ExperimentSpecValidator` 构造函数的注册 Map 里加一行。

### 3. 版本化 MATLAB 模板

- 新建 `src/main/resources/matlab/templates/<template-v1>/`：`run_experiment.m`（入口固定为 `run_experiment('matlab-input.json', '.')`）、参数加载/网格/单点/导出/绘图脚本、`TEMPLATE_MANIFEST.json`（声明 experimentType、algorithmName/Version、classification、algorithmValidated=false 等）、`README.md`。
- 注册：在 `MatlabTemplateCatalog` 的 `TEMPLATES` Map 里注册，`MatlabTemplate(version, experimentType, resourceRoot, resourceFiles)` 四参构造明确声明模板服务的类型。
- Runner 只运行这个固定模板，不接受任意脚本。

### 4. 结果契约校验器

- 实现 `org.example.wavepilot.experiment.validation.ExperimentResultContractValidator`：
  - `experimentType()` 声明类型；
  - `validate(job, byType, errors)` 校验该类型 accuracy.csv 列/数值约束、summary.json 字段、MAT/PNG 签名。
- 参考实现：`RealPolarAlgorithmResultValidator`（13 列极化码契约）。
- 注册：在 `ResultValidator` 构造的 `contractByTemplate` 里把新模板版本映射到校验器（分派键是模板版本，这样 Mock 模板/集成 fixture 仍走通用 3 列契约）。

### 5. 报告指标提取

- 实现 `org.example.wavepilot.report.ExperimentMetricsExtractor`：
  - `extract(registry, job, artifacts)` 解析本类型 CSV、Java 重算 min/max/mean 并与 summary 交叉核对，返回 `ExtractedMetrics`。
- 参考实现：`PolarKExperimentMetricsExtractor`。
- 注册：在 `ReportDataAssembler` 构造中注册。
- 注意：`ExtractedMetrics.MetricRow` 与 `ExperimentReportData.AccuracyPoint` 是当前报告数据模型的字段（codeLength/trueK/errorRate/meanEstimatedK/mae/bias 等）。新类型要么把结果映射进该形状，要么扩展报告数据模型（`ExperimentReportData`、`AccuracyPoint`、结论生成逻辑）——这是报告链路最需要改动的部分。

### 6. Replay 比较指标

- 实现 `org.example.wavepilot.replay.ExperimentComparisonMetrics`：声明该类型参与可复现性判定的数值列（如 QPSK 的 `[ber(true), mae?]`），`Metric(name, meanAlso)` 表示是否计算平均绝对差。
- 参考实现：`PolarKComparisonMetrics`（accuracy 最大+平均差，mae/bias 最大差）。
- 注册：在 `ReplayComparisonEvaluator` 构造中注册。

### 7. Agent、前端与测试

- Agent 提示词（`WavePilotAgentPrompt` / `ExperimentSpecParser.buildPrompt`）声明新类型及其参数含义。
- 前端 `METRIC_LABELS`/`ARTIFACT_TYPE_LABELS` 等中英对照表补充新值（`src/main/resources/static/app.js` 顶部）。
- 测试：类型校验器单测、模板 manifest 测试、契约校验测试、报告组装测试、Replay 比较测试，并在 Phase0To5RegressionTest 挂 Class.forName 守卫。

## 验收清单

- [ ] `ExperimentType` 枚举新值
- [ ] `ExperimentTypeValidator` 实现 + `ExperimentSpecValidator` 注册
- [ ] MATLAB 模板 + manifest + `MatlabTemplateCatalog` 注册
- [ ] `ExperimentResultContractValidator` 实现 + `ResultValidator.contractByTemplate` 注册
- [ ] `ExperimentMetricsExtractor` 实现 + `ReportDataAssembler` 注册
- [ ] `ExperimentComparisonMetrics` 实现 + `ReplayComparisonEvaluator` 注册
- [ ] 提示词/前端/测试更新
- [ ] `mvn -B clean test` 全量通过（默认套件不依赖 MATLAB/Milvus/DashScope）
- [ ] 真实模板 smoke 通过后再声称该类型"真实可跑"

## 边界（禁止）

- 不实现运行时动态插件、SPI 扫描、JAR 加载；
- 不接受用户上传任意 MATLAB 脚本（模板必须进 `MatlabTemplateCatalog` 白名单）；
- 不把 `algorithmValidated=false` 改写成已验证；
- 不把简化基线描述为论文算法。
