# WavePilot 安全边界

更新时间：2026-08-06

## 信任模型

1. **LLM 输出不可信**：自然语言解析结果必须反序列化为 `ExperimentSpec` 并通过 `ExperimentSpecValidator` 的 Java 确定性校验才能进入执行。
2. **Agent 只暴露受控工具**：`WavePilotAgentTools` 只有 10 个 `@Tool`，字段不含 `ProcessBuilder`、`ExperimentRunner`、`ExperimentJobRepository`、`Path` 或文件写接口；工具通过 `ExperimentService` 服务门面操作。
3. **Report 模型零能力**：`ReportLanguageModel` 接口只接收 `ExperimentReportData` + 模板 Markdown；修改数值、删除 Citation、删除边界（`mock=false`、`SIMPLIFIED_BASELINE`、`algorithmValidated=false`）或引入模板不存在的数值都会被 `ControlledReportAgent` 拒绝并回退模板报告。
4. **Eval 工具安全闸**：`EvaluationToolGuard.CONTROLLED_TOOLS` 与 10 个受控工具反射一致，非受控或被禁工具一律拒绝。
5. **Runner 固定模板**：本地 MATLAB 只运行版本化 catalog 模板（`MatlabTemplateCatalog`），通过 JSON 传参，不支持任意命令。

## 数据与文件

- `ArtifactRegistry`：jobId 白名单校验（`[A-Za-z0-9_-]{1,100}`）；注册与解析都拒绝目录穿越、符号链接、Job 目录外文件；每次读取前校验 SHA-256 与大小。
- metadata API 只返回 `relativePath`；`path` 字段 `@JsonIgnore`；任何 JSON 响应不包含本机绝对路径（契约测试逐项断言）。
- `application.yml` 的 API Key 只使用 `${DASHSCOPE_API_KEY:...}` 占位符，工作区禁止出现字面密钥。

## 状态与判定

- Job 状态由服务端状态机决定；前端展示服务端原值，SSE 断线自动重连，绝不把 RUNNING 显示为 SUCCEEDED。
- `mock=false` 只表示真实 Runner 进程；`classification=SIMPLIFIED_BASELINE` 表示算法类别；`algorithmValidated=false` 表示没有科研性能验证；三条轴互不替代。
- Replay 判定 REPRODUCIBLE 只表示相同配置下结构化结果在容差内一致，不代表算法已验证。
- CitationStatus（VERIFIED/PARTIAL/UNVERIFIED）只表示引用完整性，前端不做概率化展示。

## 默认与外部依赖

- 默认 Mock Runner；`WAVEPILOT_RUNNER_TYPE=local-matlab` 显式开启才启动 MATLAB。
- 默认测试不调用 DashScope、不依赖真实 Milvus、不依赖本机 MATLAB；外部依赖只在 `milvus-smoke` / `dashscope-smoke` / `matlab-smoke` / `full-demo` profile 中使用。
- 仓库仅保留 WavePilot 业务入口；历史兼容控制器与运维工具不进入构建或发布物。
