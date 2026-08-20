# Phase 5E 前端工作台指南

更新时间：2026-08-06

## 技术方式

沿用项目已有的静态 HTML/JavaScript/CSS（`src/main/resources/static/`），未引入新框架。工作台页面为 `index.html` + `app.js` + `styles.css`，通过 REST + SSE 与后端交互；所有参数校验、算法执行、报告 Grounding、Replay 判定与 Eval 指标都发生在服务端，浏览器只负责展示与操作。

## 布局

- 左侧：Agent 对话区、通信知识文档上传。
- 中间：ExperimentSpec JSON 预览与编辑、Java 参数校验结果、ExperimentPlan、Runner 类型、Job 列表、Job 状态、SSE 实时进度条、当前参数点、取消任务。
- 右侧：Artifact 列表与 metadata、CSV 下载入口、PNG 曲线预览、实验报告、Citation 列表与"定位 Artifact"、Replay 按钮与新任务状态/对比结果、Eval 运行入口与指标/失败 Case/Baseline-Candidate 比较。

## 边界展示（必须明显）

- 顶部与选中任务两处边界徽章：`MOCK EXPERIMENT`（黄色，标注"模拟实验，未运行 MATLAB"）或 `REAL MATLAB EXPERIMENT`（绿色，标注"真实 MATLAB 实验"）+ 简化基线（`SIMPLIFIED_BASELINE`）+ 算法未验证（`algorithmValidated=false`）；来源于 ArtifactRecord 的 `mock/runnerType/classification/algorithmValidated` 字段，不是前端猜测。
- 任务状态永远直接展示服务端值（中英对照，如"运行中（RUNNING）"）；SSE 断线提示自动重连，绝不把 RUNNING 显示为 SUCCEEDED。
- similarityScore、CitationStatus、Replay 一致性判定都以状态标签/文字展示，不做百分比或概率格式化（契约测试逐行校验）。
- Replay 判定旁固定提示"Replay 一致不代表算法已验证（algorithmValidated=false 始终保留）"。

## 展示语言与布局约定

- 界面遵循"中文为主、技术标识（英文枚举）为辅"：状态、阶段、指标、Artifact 类型、Citation 状态、Replay 判定、布尔值全部中英对照展示（映射集中在 app.js 顶部的 `STATUS_LABELS`/`STAGE_LABELS`/`ARTIFACT_TYPE_LABELS`/`METRIC_LABELS`，新增枚举只需补一处映射）。
- 布局限高：每个面板 `max-height: calc(100vh - 88px)` 独立滚动；所有结果文本区（metadata、校验结果、Replay/Eval 输出等）统一 `max-height: 220px` 内滚动；报告区 420px 内滚动。内容再多也只在面板内滚动，页面整体不会无限拉长。

## 错误处理

请求失败、Job 不存在（404）、Artifact 校验失败/失效、Replay 失败、Eval Case 失败、报告未生成均有明确错误文案与 toast 提示；SSE 断线时以 REST 轮询为准。

## 契约测试

`FrontendApiContractTest`、`FrontendSseContractTest`、`FrontendMockRealBoundaryTest`、`FrontendCitationDisplayContractTest`、`FrontendReplayContractTest`、`FrontendEvaluationContractTest`（共 30 项）直接读取静态资源断言：API 覆盖、SSE 行为、边界文案、Citation 非概率展示、Replay/Eval 交互契约，以及静态文件不含本机绝对路径。

## 演示建议

1. 直接打开 `http://localhost:9900/`（`mvn spring-boot:run` 默认端口 9900）。
2. 中间栏粘贴合法 Spec → Java 校验 → 创建任务 → 观察 SSE 进度 → 查看 Artifact 与边界徽章。
3. 生成报告 → 查看 Citation → 点击"定位 Artifact"。
4. 对 SUCCEEDED 任务执行 Replay → 查看 REPRODUCIBLE 判定。
5. 运行 Eval（stub-v1 / stub-v2）→ 查看指标与失败 Case → 比较两次运行。
6. 真实 MATLAB 演示需设置 `WAVEPILOT_RUNNER_TYPE=local-matlab`（见 README）。
