# WavePilot 最终实现报告

更新时间：2026-08-06

## 已真实实现（Java 确定性代码）

- Java 参数校验：`ExperimentSpecValidator`（码长 2 的幂、错误率范围、资源风险警告）。
- 状态机：`ExperimentStateMachine`（CREATED→VALIDATED→QUEUED→RUNNING→VALIDATING_RESULT→SUCCEEDED/FAILED/CANCELLED）。
- 异步任务：`ExperimentService` 线程池编排，Runner 轮询、取消、失败登记。
- SSE：`/api/experiments/{jobId}/stream` 实时进度事件。
- Artifact：`ArtifactRegistry` 登记、SHA-256、大小、MIME、相对路径、穿越/符号链接拒绝、verify/resolveVerified。
- Citation：`ArtifactCitation` 定位 Artifact SHA-256、CSV 行/字段、JSON 字段原值；跨 Job、篡改、未验证产物拒绝。
- 模板报告：`TemplateExperimentReportGenerator` 生成不依赖模型的 JSON/Markdown 报告；`ReportDataAssembler` 从已验证 CSV/summary 计算 min/max/mean 并交叉校验。
- Local MATLAB Runner：`LocalMatlabExperimentRunner` 只运行版本化固定模板，JSON 传参，进程树超时/取消、日志与 CSV/MAT/PNG/summary 产物；`ResultValidator` 校验 MAT/PNG 签名与结构化一致性。
- Replay：`ReplayService` 独立新 Job、确定性 Fingerprint、结构化比较、REPRODUCIBLE 判定、REPLAY_MANIFEST/REPLAY_COMPARISON 产物。
- 离线 Eval：24 个固定 Case、11 项指标由执行结果计算、Baseline/Candidate 配对比较、逐 Case 结果保留。
- external-eval profile：`ExternalEvaluationModel` 走真实 DashScope `ExperimentSpecParser` 执行 Spec 类 Case，未覆盖 Case 显式记录 NOT_COVERED；未运行时不得声称真实模型 Eval 通过。
- 前端工作台：静态三栏页面，完整闭环展示与操作（见 FRONTEND_GUIDE.md）。

## Mock/Fake（明确标注，不冒充真实）

- 默认测试模型：`ReferenceStubModel`（stub-v1）/ `RegressedStubModel`（stub-v2）脚本化模型，缺陷是脚本化行为。
- 默认知识库：内存 Repository + 确定性 Embedding，仅离线测试。
- Mock Runner：`MockExperimentRunner` 生成确定性数值与 3 列 CSV，只验证软件闭环。
- 离线 Eval Agent：stub 模型；平台侧执行器全部真实。

## 需要外部环境（profile 显式启用，未运行时不得声称通过）

- DashScope：`dashscope-smoke`（三项最小 smoke 已就绪，真实运行延至最终演示前）。
- 真实 Milvus：`milvus-smoke`。
- MATLAB smoke：`matlab-smoke`。
- full-demo：`full-demo`（Milvus + DashScope + MATLAB + 前端）。

## 算法边界（不可夸大）

- `mock=false` 只代表真实 MATLAB Runner 进程执行。
- `classification=SIMPLIFIED_BASELINE`：简化业务基线，不是论文复现或创新算法。
- `algorithmValidated=false`：没有科研性能验证。
- Replay 一致、报告生成、Eval 通过都不构成算法科研结论。

## 当前持久化限制

- Job/Replay/Eval Repository 均为内存实现，应用重启后任务元数据可能丢失。
- Artifact 文件保存在磁盘（`wavepilot.artifacts.root`）。
- 未实现 MySQL 或任何关系型持久化；不得描述为已完成 MySQL 持久化。

## 验证结果

- 默认离线套件 182 项全部通过（Java 17 编译目标；本机 Windows 实测 `mvn -B clean test` BUILD SUCCESS）。
- Docker Eclipse Temurin 17.0.15 复验为 Phase 5F 的待办记录项。
- 没有 GitHub 远端；GitHub Actions 仍为已配置、无真实 run。
