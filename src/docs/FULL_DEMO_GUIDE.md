# Full Demo 指南

更新时间：2026-08-06

## 前提条件（全部必需）

- 真实 Milvus（默认 localhost:19530，`wavepilot_knowledge_v1` collection）；
- DashScope API Key（`DASHSCOPE_API_KEY`）；
- 本机 MATLAB（`MATLAB_EXECUTABLE`）；
- WavePilot 静态工作台（已包含在 `src/main/resources/static`，`mvn spring-boot:run` 后访问 `http://localhost:9900/`）。

## 运行完整演示

```powershell
$env:WAVEPILOT_RUNNER_TYPE = "local-matlab"
$env:MATLAB_EXECUTABLE = "D:\Program Files\MATLAB\R2023b\bin\matlab.exe"
$env:DASHSCOPE_API_KEY = "<真实密钥>"
mvn spring-boot:run
```

打开 `http://localhost:9900/`，演示顺序：

1. 对话区用自然语言描述实验（模型服务可用时展示 Spec 解析与缺参追问）；
2. 中间栏粘贴结构化 Spec → Java 校验 → 创建任务 → SSE 实时进度 → 当前参数点；
3. 查看 Artifact 列表与边界徽章：`REAL MATLAB EXPERIMENT | SIMPLIFIED_BASELINE | algorithmValidated=false`；
4. 生成报告 → 查看 Citation → 点击"定位 Artifact"；
5. 对 SUCCEEDED 任务执行 Replay → 查看 REPRODUCIBLE 判定与对比指标；
6. 运行 Eval（stub-v1/stub-v2）→ 查看指标与失败 Case → 比较两次运行。

## 自动验证

```powershell
mvn -B -Pfull-demo verify
```

`FullDemoIT` 断言环境已配置（API Key 非空、MATLAB 可执行路径非默认占位）且前端资源存在。**没有真实运行时，不得声称 full-demo 已通过**；该 profile 默认不执行。

## 真实与未验证边界

- 真实 MATLAB 执行与真实 Milvus 检索在演示中可见；自然语言解析/Embedding/Agent 对话依赖 DashScope 额度。
- Replay 一致、报告完整都**不**构成算法科研验证：`algorithmValidated=false` 与 `SIMPLIFIED_BASELINE` 全程展示。
- 任务元数据仍为内存 Repository，重启丢失；持久化 MySQL 未实现。
