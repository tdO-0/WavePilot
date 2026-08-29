# WavePilot

[![Java 17 CI](https://github.com/tdO-0/WavePilot/actions/workflows/java17-ci.yml/badge.svg)](https://github.com/tdO-0/WavePilot/actions/workflows/java17-ci.yml)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

面向通信仿真实验的 Agent 平台：将自然语言实验需求编排为受控的 `ExperimentSpec`，完成 Java 确定性校验、异步执行、Artifact 完整性校验、带 Citation 的报告、Replay 复现和离线 Eval。Scientific Agent 模式进一步提供规划、执行、验证、受限 Replan、checkpoint 恢复和 Trace 实验闭环。

> 该仓库于 2026 年 8 月整理后公开，项目开发此前主要在本地完成。

<p align="center">
  <img src="docs/assets/wavepilot-workbench.png" alt="WavePilot 结果与证据工作台真实界面" width="100%">
</p>

界面截图来自仓库当前版本的实际运行：固定随机种子 `20`、离线 Mock Runner、内存知识库。页面会明确显示“未运行 MATLAB”和 `algorithmValidated=false`，不会把软件闭环演示包装成科研性能结论。

## 30 秒演示

<p align="center">
  <img src="docs/assets/wavepilot-demo.gif" alt="WavePilot 30 秒真实操作演示" width="100%">
</p>

演示依次覆盖：目标输入 → Spec 校验 → 6/6 参数点完成 → 5 个 Artifact 校验 → Citation 报告 → Replay 零差异 → 24/24 离线 Eval。

## 可复现证据

仓库自带固定输入、期望 CSV、期望指标和一键校验脚本：[examples/reproducible-showcase](examples/reproducible-showcase)。本地实际运行结果如下：

| 检查项 | 结果 |
|---|---:|
| Maven 单元/契约/集成测试 | 375/375 通过 |
| 固定实验参数点 | 6/6 完成 |
| 已登记且通过完整性校验的 Artifact | 5/5 |
| Accuracy（min / max / mean） | 0.901584 / 0.957320 / 0.9283471667 |
| Replay | `REPRODUCIBLE` |
| Replay accuracy 最大绝对差 | 0（容差 `1e-9`） |
| 离线 Eval Case | 24/24 通过 |
| 值为 1.0 的 Eval 指标 | 11/11 |

这些指标用于验证编排、产物契约、数值 Grounding、Replay 和 Eval 链路；它们不是通信算法 benchmark，也不代表真实 MATLAB 执行结果。

## 快速开始

需要 JDK 17+ 和 Maven 3.9+。默认使用安全的 Mock Runner；下面的配置完全离线，不需要 API Key、Milvus 或 MATLAB。

```powershell
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:DASHSCOPE_API_KEY = "not-configured"
mvn -B clean test
mvn spring-boot:run
```

打开 <http://localhost:9900>。另开一个 PowerShell 复现 GitHub 展示案例：

```powershell
.\examples\reproducible-showcase\run.ps1
```

脚本任一断言失败都会返回非零退出状态；成功时输出 Job、Replay、Eval 编号及全部量化结果。固定期望值见 [expected/metrics.json](examples/reproducible-showcase/expected/metrics.json)。

Docker 全栈启动：

```powershell
docker compose up -d --build
```

该方式会启动 WavePilot 与 Milvus；默认仍为 Mock Runner。

## 核心能力

- Agent 编排：自然语言目标、缺参补充、受控工具调用和人工审批边界。
- 实验执行：Java 状态机、异步 Job、SSE 实时进度、取消和固定模板 Runner。
- 证据链：Artifact SHA-256、结构化结果校验、数值到 CSV 行/字段的 Citation。
- 报告：确定性模板报告；可选受控模型只润色已 Grounded 的数据。
- Replay：保留原随机种子并创建独立任务，比较 Fingerprint、CSV 和 summary。
- Eval：24 个固定 Case 覆盖解析、校验、工具安全、提交、引用、Grounding 与 Replay。
- 模板治理：候选生成、安全校验、Smoke、显式批准、版本激活与回滚。
- 知识库：内存离线模式或 Milvus metadata 过滤，可选 DashScope Embedding。
- Hybrid Retrieval：Milvus/内存 Dense + Apache Lucene BM25，经稳定 `chunkId` 执行 RRF，可选确定性或模型 Rerank，并保留 Citation provenance。
- Scientific Agent：`Goal → Plan → Retrieve → Execute → Observe → Verify → Replan/Finish`，由 Java 强制预算、参数边界和终止条件。
- Durable AgentRun：原子 JSON checkpoint 记录计划、执行、观察、验证、Replan 与 Trace；恢复时复用已完成的 Observation 和 Artifact。

```text
实验目标
  → ExperimentSpec / Java 校验
  → 受控 Runner / 状态机 / SSE
  → Artifact 登记与 SHA-256 校验
  → Citation 报告
  → Replay + Offline Eval
```

Scientific Agent 闭环：

```text
ExperimentGoal
  → constrained ScientificExperimentPlan
  → Hybrid retrieval evidence
  → ExperimentService.create(validatedSpec, executionId)
  → validated Artifact observation
  → deterministic Verifier
  → bounded Replanner or terminal state
```

## 运行模式与边界

| 模式 | 配置 | 用途 |
|---|---|---|
| 确定性离线演示 | `WAVEPILOT_RUNNER_TYPE=mock` | 无外部依赖地验证完整软件链路 |
| 本机 MATLAB | `WAVEPILOT_RUNNER_TYPE=local-matlab` | 在可信主机上执行仓库内固定 MATLAB 模板 |
| 离线知识库 | `memory` + `WAVEPILOT_EMBEDDING_OFFLINE=true` | 测试与演示 |
| 持久化知识库 | `milvus` + 有效 DashScope Key | 真实向量检索 |

## 接入真实 DashScope 与 MATLAB

1. 将 `.env.example` 复制为 `.env`。
2. 填写 `DASHSCOPE_API_KEY` 和本机 `MATLAB_EXECUTABLE`。
3. Windows 运行 `start-full.bat`，Linux/macOS 运行 `./start-full.sh`。

真实 MATLAB 模式使用固定模板和参数文件启动受控子进程，不允许模型直接拼接任意 MATLAB 命令。候选模板必须经过安全检查、Smoke Test 和人工审批才能发布。

## 主要 API

| 能力 | API |
|---|---|
| 实验参数解析 | `POST /api/experiments/spec/parse` |
| 创建/查询实验 | `POST /api/experiments`、`GET /api/experiments/{jobId}` |
| 实验进度流 | `GET /api/experiments/{jobId}/stream` |
| 自主 Agent 会话 | `POST /api/autonomous/start` |
| 模板与候选管理 | `/api/wavepilot/templates`、`/api/wavepilot/template-candidates` |
| 知识上传与检索 | `/api/wavepilot/knowledge/upload`、`/api/wavepilot/knowledge/search` |
| 报告与引用 | `/api/experiments/{jobId}/report`、`/api/experiments/{jobId}/citations` |
| 重放与评测 | `/api/replays`、`/api/evaluations` |
| Scientific Agent | `POST /api/scientific-agent/runs`、`POST /api/scientific-agent/runs/{runId}/resume` |
| Retrieval Eval | `POST /api/retrieval-evaluations/run`、`GET /api/retrieval-evaluations/{id}/report.md` |
| Agent Regression Eval | `POST /api/agent-regression-evaluations/run`、`POST /api/agent-regression-evaluations/compare` |

更多请求示例见 [`src/docs/API_EXAMPLES.http`](src/docs/API_EXAMPLES.http)。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `9900` | HTTP 端口 |
| `DASHSCOPE_API_KEY` | `not-configured` | DashScope Chat/Embedding 凭据 |
| `WAVEPILOT_RUNNER_TYPE` | `mock` | `mock` 或 `local-matlab` |
| `MATLAB_EXECUTABLE` | `matlab` | MATLAB 可执行文件路径 |
| `WAVEPILOT_KNOWLEDGE_REPOSITORY` | `milvus` | `milvus` 或 `memory` |
| `WAVEPILOT_EMBEDDING_OFFLINE` | `false` | 使用确定性离线向量 |
| `WAVEPILOT_ARTIFACT_ROOT` | `artifacts` | 实验产物目录 |
| `WAVEPILOT_AGENT_RUN_STORE` | `data/wavepilot/agent-runs` | AgentRun checkpoint 目录 |
| `WAVEPILOT_DENSE_CANDIDATE_K` | `20` | Dense 候选数 |
| `WAVEPILOT_SPARSE_CANDIDATE_K` | `20` | BM25 候选数 |
| `WAVEPILOT_RRF_K` | `60` | Reciprocal Rank Fusion 常数 |

Mock 输出始终带 `mock=true`。`algorithmValidated=false` 表示简化基线尚未经过论文或标准级科学验证；Replay 一致只证明同一输入可复现。

## 技术栈

- Java 17、Spring Boot 3.2、Maven
- Spring AI Alibaba / DashScope（可选）
- Milvus Java SDK（可选，离线模式不需要）
- 原生 HTML/CSS/JavaScript 工作台
- JUnit 5、Spring Boot Test、GitHub Actions

## 实际验证结果（2026-08-30）

### Software Engineering Validation

- 目标仓库基线：`mvn test` 实际运行 363 项，363 成功、0 失败。
- 本次新增定向测试实际覆盖 Java Validator 不可绕过、Replan 参数边界、预算终止、幂等执行、checkpoint 恢复、Dense/BM25/RRF 排序、双路 metadata filter、Citation provenance、检索指标、Mock Scientific Agent 闭环和 Agent regression。
- 升级后最终 `mvn -B test`：375 项、375 成功、0 Failure、0 Error、0 Skip，`BUILD SUCCESS`。不得把默认测试解释为 MATLAB 或通信算法科研验证。

### Retrieval Evaluation

固定 6 Case、Top-3 的确定性离线语料实际结果如下。指标由运行时结果计算，未硬编码：

| Strategy | Recall@3 | Precision@3 | MRR | nDCG@3 | Citation Hit Rate |
|---|---:|---:|---:|---:|---:|
| Dense Only | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |
| BM25 Only | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |
| Hybrid RRF | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |
| Hybrid RRF + Rerank | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |

该小型精确匹配数据集只能验证链路、过滤、排序和指标实现；四种策略打平，不能声称 Hybrid 提升了检索质量。

### Agent Evaluation

- 离线阈值搜索案例实际从初始 error-rate 区间开始，运行 3 次 Mock 实验、执行 2 次有界 Replan 后终止为 `SUCCEEDED`；最终 grounded `averageAccuracy=0.898440`。
- Durable Recovery 测试从 Observe 后 checkpoint 恢复，复用原 job 与 1 组已验证 Observation，没有创建第二次实验副作用。
- Agent regression 以实际 AgentRun、Retrieval Eval 与 Replay 记录计算 9 个维度；它衡量软件行为，不衡量科研效果。

### Scientific Algorithm Validation

本次没有新增真实 MATLAB 科研 benchmark，没有证明通信算法准确率、性能、收敛性或优于基线。Mock 的 `averageAccuracy` 是确定性软件夹具，绝不能写成论文、简历或产品性能指标。

## 能力边界

- 默认 Mock 数据只用于工作流演示与契约测试，不代表真实通信算法结果。
- 内置与生成的实验模板均保持 `algorithmValidated=false`；Smoke Test 只证明可执行，不构成算法正确性证明。
- 当前 MATLAB 集成为宿主机本地进程 Runner，尚未实现 MATLAB MCP Server。
- Scientific `AgentRun` 已使用文件 checkpoint；普通 ExperimentJob、AutonomousSession、候选模板、Replay/Eval 的运行态仍主要在内存中，应用重启后的完整跨模块恢复尚未实现。
- Lucene BM25 当前是进程内可重建索引。Compose Milvus Server 为 2.5.10、Java SDK 为 2.6.10，因此没有宣称原生 Sparse/Hybrid 的跨版本稳定性。
- 真实模式依赖用户自行提供的 DashScope、Milvus 和 MATLAB 环境，CI 只验证离线 Mock/契约路径。

## 进一步阅读

- [自主 Agent 模式](AUTONOMOUS_MODE.md)
- [架构说明](src/docs/ARCHITECTURE.md)
- [Artifact 证据模型](src/docs/ARTIFACT_PROVENANCE.md)
- [Replay 设计](src/docs/REPLAY_DESIGN.md)
- [Eval 设计](src/docs/EVAL_DESIGN.md)
- [前端工作台](src/docs/FRONTEND_GUIDE.md)
- [安全边界](src/docs/SECURITY_BOUNDARIES.md)
- [扩展实验类型](src/docs/EXPERIMENT_TYPE_EXTENSION_GUIDE.md)
- [`src/docs/KNOWLEDGE_BASE_GUIDE.md`](src/docs/KNOWLEDGE_BASE_GUIDE.md)：知识库配置与使用
- [`src/docs/PHASE4_LOCAL_MATLAB.md`](src/docs/PHASE4_LOCAL_MATLAB.md)：本地 MATLAB Runner
- [`src/docs/HYBRID_RETRIEVAL.md`](src/docs/HYBRID_RETRIEVAL.md)：Dense/BM25/RRF/Rerank 与检索评测
- [`src/docs/SCIENTIFIC_AGENT_LOOP.md`](src/docs/SCIENTIFIC_AGENT_LOOP.md)：Scientific Agent 状态闭环
- [`src/docs/DURABLE_EXECUTION.md`](src/docs/DURABLE_EXECUTION.md)：checkpoint、幂等与恢复语义

## License

[Apache License 2.0](LICENSE)
