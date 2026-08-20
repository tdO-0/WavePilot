# Phase 5A/5B Report Implementation

更新时间：2026-08-06

## 实现链路

```text
SUCCEEDED Job + validated ArtifactRecords
  -> ReportDataAssembler
  -> ExperimentReportData + ArtifactCitation
  -> ReportCitationValidator
  -> TemplateExperimentReportGenerator
  -> optional ControlledReportAgent
  -> JSON + Markdown report
```

生成前要求 Job 为 `SUCCEEDED`、所有来源 Artifact 为 `validated=true`、文件大小与 SHA-256 未变化、CSV/summary 统计一致。引用校验覆盖 Artifact 存在性、Job 归属、哈希、CSV 行列、JSON 字段、原值、数值 Conclusion、跨 Job 和未验证产物。

模板报告包含实验目的、配置、算法说明、执行环境、结果摘要、参数趋势、边界、Artifact 来源、可复现信息和真实性声明。正式算法固定描述为“基于 BSC、逆向极化变换和二项似然判决的简化极化码码维数识别基线”。

本阶段没有实现 Replay、Eval、前端、MCP MATLAB、MySQL、PDF/Word 或额外 DashScope smoke。

## 测试结果

- Windows Oracle JDK 22.0.2：`mvn -B clean test`，75/75 通过。
- Docker Eclipse Temurin 17.0.15：`mvn -B clean test`，75/75 通过。
- 原 Phase 4.5 的 64 项测试全部保留，新增 11 项报告/引用回归。
- Java 17 首轮发现 SHA-256 片段被数值扫描器误判为科学计数法；收紧 token 边界后完整回归通过。

## 示例

报告结论：

```text
当 N=64、BSC ε=0.1 时，识别准确率为 0.5 [CIT-<JOB>-NNN]
```

对应 Citation 结构：

```json
{
  "citationId": "CIT-<JOB>-NNN",
  "jobId": "JOB-...",
  "artifactId": "ART-...",
  "artifactType": "ACCURACY_CSV",
  "fieldName": "accuracy",
  "rowReference": "6",
  "value": 0.5,
  "unit": "ratio",
  "artifactSha256": "<registered SHA-256>"
}
```

尖括号表示运行期生成的标识或哈希，不是固定值；真实 API 返回 Registry 中的完整值。
