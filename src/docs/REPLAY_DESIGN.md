# Phase 5C Replay 设计

更新时间：2026-08-06

## 目标与边界

Replay 用相同配置重新执行一次原实验，并比较结构化结果。Replay 创建**独立的**新 ExperimentJob，绝不覆盖原 Job、原 Artifact 或原报告。科研数值一致性只按结构化 CSV 与 summary 比较；MAT、PNG 和日志可能因时间、元数据或绘图环境不同而哈希不同，不参与字节级比较。

Replay 一致**不代表科研算法已验证**。`mock=false` 仍只表示真实 MATLAB 执行，`classification=SIMPLIFIED_BASELINE`、`algorithmValidated=false` 在 manifest 与 comparison 中始终保留。

## 流程

```text
SUCCEEDED source job + validated artifacts
  -> requireReplayableSource（状态、关键 Artifact、validated、哈希）
  -> ReplayFingerprint（规范化 ExperimentSpec + seed + runner + template + MATLAB 脚本 SHA-256 + algorithm 轴 + 关键 Artifact 哈希）
  -> ReplayManifest
  -> 创建独立 replay ExperimentJob（保留 randomSeed，sourceJobId 回链，同一 Runner 执行）
  -> 轮询至终止状态
  -> ReplayComparisonEvaluator 比较 CSV/summary
  -> 登记 REPLAY_MANIFEST + REPLAY_COMPARISON Artifact（replay Job 目录内）
```

## Replay Fingerprint

复用 Phase 5A/5B 已有的 `ReplayFingerprintInput` + `ReplayFingerprintService`（规范化字段顺序的 SHA-256）。输入覆盖：

- 规范化 ExperimentSpec JSON（Jackson `SORT_PROPERTIES_ALPHABETICALLY` + `ORDER_MAP_ENTRIES_BY_KEYS`）；
- randomSeed；
- templateVersion；
- MATLAB 模板资源 SHA-256（`MatlabTemplateDigest` 对 catalog 内模板按排序文件名+内容计算；非 catalog 模板如 mock 使用 `fallback:` 前缀的版本串摘要，明确它不是真实 MATLAB 脚本摘要）；
- runnerType、algorithmName、algorithmVersion、classification（关键运行配置）；
- 关键 Artifact（spec/plan/csv/summary）的 SHA-256 列表。

相同输入必得相同 Fingerprint；任何轴变化都会改变 Fingerprint。

## ReplayManifest

包含 replayId、sourceJobId、replayJobId、experimentType、canonicalExperimentSpec、randomSeed、runnerType、templateVersion、algorithmName、algorithmVersion、classification、mock、algorithmValidated、matlabTemplateSha256、javaApplicationVersion、replayFingerprint、createdAt。

## 比较维度

严格一致（不一致即 NOT_REPRODUCIBLE）：

- 规范化 ExperimentSpec；
- randomSeed；
- runnerType（取 ArtifactRecord，真实 Runner 身份）；
- templateVersion；
- algorithmVersion（summary.json）；
- CSV 行数；
- 参数网格（codeLength × errorRate 全集合）。

数值容差（`wavepilot.replay.numeric-tolerance`，默认 1.0e-9）：

- accuracy 最大绝对差、平均绝对差；
- MAE 最大差；
- bias 最大差；
- MAE/bias 列双方都不存在（如 3 列 mock CSV）时记为 absent，不参与判定；只有一方存在时视为 CSV 契约不一致。

最终判定：`REPRODUCIBLE` / `NOT_REPRODUCIBLE`，附带逐项 message。

## API

- `POST /api/experiments/{jobId}/replay`
- `GET /api/replays`
- `GET /api/replays/{replayId}`
- `GET /api/replays/{replayId}/comparison`
- `GET /api/replays/{replayId}/manifest`

## 安全边界

- 源 Job 必须 SUCCEEDED，关键 Artifact 必须存在、validated=true 且 SHA-256/大小未变化；
- replay Job 目录独立，不共享、不覆盖源目录；
- manifest/comparison 只写入 replay Job 目录；
- 源 Artifact 的 SHA-256 与内容在 Replay 前后保持不变（测试断言）；
- manifest 暴露的路径只有 `relativePath`，无本机绝对路径。

## 测试

新增 11 个类（28 项）：ReplayFingerprintDeterminismTest、ReplayFingerprintDifferenceTest、ReplaySourceJobValidationTest、ReplayJobIsolationTest、ReplayOriginalArtifactProtectionTest、ReplayComparisonTest、ReplayToleranceTest、ReplayArtifactRegistrationTest、ReplayControllerContractTest、Phase5BRegressionTest，以及既有 ReplayFingerprintTest。

默认测试使用 `DeterministicPolarRunner` 测试双（写入 13 列真实模板契约的 CSV/summary），不依赖 MATLAB。其 summary 按现有 validator 契约固定 `runnerType=local-matlab`、`mock=false`（`RealPolarAlgorithmResultValidator` 硬编码要求）；真实 Runner 身份通过 ArtifactRecord.runnerType()（deterministic-test）与 manifest 透明呈现。

## 未验证边界

- 真实 MATLAB 端到端 Replay 未纳入默认测试；可选 `matlab-smoke` profile 可做一次小规模真实 Replay，未运行时不得声称已通过。
