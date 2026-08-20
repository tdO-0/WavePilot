# Phase 5D 离线 Eval 设计

更新时间：2026-08-06

## 目标与边界

Eval 用固定测试 Case 评价 **Agent 与平台流程**（Spec 解析、缺参追问、Java 校验、工具选择、工具安全、Job 创建与状态、Artifact Citation、报告 Grounding、Replay 一致性），**不是**极化码算法的科研精度。

默认离线 Eval 使用脚本化 Stub/Fake Model（`stub-v1` 参考实现、`stub-v2` 带脚本化缺陷），**不调用 DashScope**；平台侧（Java Validator、工具安全闸、ExperimentService、ReplayService、ReportCitationValidator）全部是真实组件。真实模型 Eval 属 `external-eval` profile，默认关闭；未真实运行时不得声称外部 Eval 已通过。

## 数据集

24 个固定 Case（每种 CaseType 2 个）＋固定知识语料（2 个 chunk，由检索执行器幂等 upsert）：

| CaseType | 验证内容 |
|---|---|
| COMPLETE_SPEC | 完整自然语言解析出的 Spec 通过 Java 校验且包含全部预期字段 |
| MISSING_PARAMETER | 缺参被识别并列出正确缺失字段 |
| INVALID_PARAMETER | 非法参数被 Java Validator 拦截且错误提及预期字段 |
| KNOWLEDGE_RETRIEVAL | 真实知识库检索命中预期内容 |
| TOOL_SELECTION | Stub 选择的工具与预期工具一致 |
| TOOL_SECURITY | 工具安全闸拒绝非受控/被禁工具；合法路径只允许受控工具 |
| JOB_SUBMISSION / JOB_STATUS / JOB_CANCEL | 真实 ExperimentService 状态机与受控 Runner |
| ARTIFACT_CITATION / REPORT_GROUNDING | 报告链：引用全部指向已验证 Artifact，数值结论有 Citation 原值支撑 |
| REPLAY_CONSISTENCY | 完整 source→replay 链路判定 REPRODUCIBLE |

每个 Case 保存：caseId、caseType、description、input、expectedResult、expectedTool、forbiddenTools、expectedStatus、expectedFields、tags，以及执行后的 passed、actualResult、actualTool、failureReason。

## 指标

全部由 Case 实际执行结果计算（numerator/denominator），禁止写死百分比：

specParseAccuracy、missingParameterDetectionRate、invalidParameterBlockRate、toolSelectionAccuracy、forbiddenToolBlockRate、jobSubmissionSuccessRate、knowledgeRetrievalRate、artifactCitationConsistencyRate、reportGroundingRate、replayConsistencyRate、overallTaskCompletionRate。

## 工具安全

`EvaluationToolGuard.CONTROLLED_TOOLS` 与 `WavePilotAgentTools` 的 10 个 `@Tool` 完全一致（测试反射校验）。安全闸拒绝：非受控工具、被显式禁止的工具；Agent 永远无法通过 Eval 触达进程、文件或 Repository。

## Baseline / Candidate

`POST /api/evaluations/compare` 对**同一数据集**的两个 Run 做配对比较：每项指标的 baseline/candidate/delta、退化 Case 列表、新增通过 Case 列表、releaseAllowed（无退化 Case 且无指标下降）。逐 Case 结果始终保留，禁止只看总分。

## API

- `POST /api/evaluations/run`（datasetName、modelName；默认 stub-v1）
- `GET /api/evaluations`
- `GET /api/evaluations/{evaluationId}`
- `GET /api/evaluations/{evaluationId}/report`
- `POST /api/evaluations/compare`

ArtifactType 增加 `EVAL_REPORT`、`EVAL_CASE_RESULTS`、`EVAL_COMPARISON`，登记在 evaluationId 目录下。

## 测试

11 个新测试类（35 项）：EvaluationDatasetTest、EvaluationCaseExecutionTest、EvaluationMetricCalculationTest、EvaluationNoHardcodedMetricTest、EvaluationToolSecurityTest、EvaluationReportGroundingTest、EvaluationArtifactRegistrationTest、EvaluationBaselineCandidateTest、EvaluationRegressionDetectionTest、EvaluationControllerContractTest、ReplayRegressionTest。

默认测试使用内存知识库与确定性 Embedding（`EvaluationTestSupport.DeterministicEmbeddingService`，同词同向量），不依赖 Milvus/MATLAB/DashScope。

## 真实 Runner 注意事项

Job/Replay 类 Case 的等待上限由 `wavepilot.evaluation.job-wait-timeout-millis` 控制（默认 10000 ms，适合毫秒级 Mock Runner）。使用真实 MATLAB Runner 时需调大（如 `WAVEPILOT_EVAL_JOB_WAIT_MILLIS=300000`），否则 Job/Replay Case 会以 `WAIT_TIMEOUT` 明确失败——任务本身仍在执行，不是平台故障。应用内离线运行 Eval（`mvn spring-boot:run` 演示）需要同时设置 `WAVEPILOT_KNOWLEDGE_REPOSITORY=memory` 与 `WAVEPILOT_EMBEDDING_OFFLINE=true`，否则内存知识库的检索仍会调用 DashScope Embedding 并返回 401。

## 未验证边界

- `external-eval` profile（`mvn -B -Pexternal-eval -DDASHSCOPE_API_KEY=... verify`）会用真实 DashScope 解析器执行 Spec 类 Case；工具/平台类 Case 明确记录为 NOT_COVERED。未真实运行时不得声称真实模型 Eval 已通过。
- stub-v2 的"退化"是脚本化缺陷，不代表真实模型表现。
- Eval 指标衡量流程正确性，不是科研算法精度。
