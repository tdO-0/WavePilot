# WavePilot

[![Java 17 CI](https://github.com/tdO-0/WavePilot/actions/workflows/java17-ci.yml/badge.svg)](https://github.com/tdO-0/WavePilot/actions/workflows/java17-ci.yml)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

WavePilot 是面向通信仿真实验的受控 Scientific Agent 平台。它把自然语言目标转换为经过 Java 校验的 `ExperimentSpec`，编排知识检索、异步实验、Artifact 完整性校验、Grounded 报告、Replay 与离线评测，并通过持久化 Execution Ledger 避免重启后重复执行已完成实验。

默认配置使用离线 Mock Runner。Mock 指标只验证软件链路，不代表 MATLAB 或通信算法科研结果。

<p align="center">
  <img src="docs/assets/wavepilot-workbench.png" alt="WavePilot 结果与证据工作台" width="100%">
</p>

## Core Architecture

```text
ExperimentGoal
  -> Structured Plan (registered capabilities only)
  -> Hybrid RAG (Dense + Lucene BM25 + RRF + controlled rerank)
  -> ExperimentSpecValidator + RunBudget + ParameterBounds
  -> Idempotent Experiment Execution + Execution Ledger
  -> Validated Artifact Observation
  -> Deterministic Verification
  -> Bounded Replan or Terminal State
  -> Replay + Retrieval Eval + Agent Regression Eval
```

模型只能提出结构化计划、候选排序或下一组 Spec；Java 仍负责能力白名单、Schema、参数边界、预算、Artifact hash 和最终执行授权。模型不能生成或执行任意 Java、MATLAB、Shell 或自定义 executable。

## 30-Second Demo

<p align="center">
  <img src="docs/assets/wavepilot-demo.gif" alt="WavePilot 30 秒操作演示" width="100%">
</p>

演示覆盖目标输入、Spec 校验、Mock 实验、Artifact 校验、Citation 报告、Replay 和离线 Eval。可复现输入、期望 CSV 与校验脚本位于 [examples/reproducible-showcase](examples/reproducible-showcase)。

```powershell
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:DASHSCOPE_API_KEY = "not-configured"
mvn spring-boot:run
```

打开 <http://localhost:9900>，或另开 PowerShell 执行：

```powershell
.\examples\reproducible-showcase\run.ps1
```

## Scientific Agent Loop

`Goal -> Plan -> Retrieve -> Execute -> Observe -> Verify -> Replan/Finish`

- `ScientificPlanner` 默认输出确定性安全计划；可选模型 Planner 只能选择已注册 `ScientificCapability`。
- `BoundedScientificReplanner` 默认执行确定性参数调整；可选语义 Replan 仍必须通过 `ParameterBounds`、最大单步变化、`ExperimentSpecValidator` 和剩余预算。
- `RunBudget` 限制 iterations、experiments、model calls、tokens、retries 与 wall-clock timeout。
- AgentRun 在每个关键阶段原子 checkpoint；Execution Ledger 持久化 executionId、Spec fingerprint、jobId、状态与 Artifact 引用。

## Hybrid RAG

- Dense：Milvus 或离线内存向量库。
- Sparse：进程内 Apache Lucene BM25；通信领域 Analyzer 支持中文 bigram、英文、数字、`BPSK/AWGN/BER/BLER`、`Eb/N0`、camelCase 和 snake_case。
- Fusion：按稳定 `chunkId` 执行 Reciprocal Rank Fusion，不线性混合 Dense 与 BM25 原始分数。
- Routing：用户显式 metadata filter 是 hard filter；Router 推断仅提供 document-type boost，并保留其他类型候选。
- Rerank：确定性 term-overlap 是离线 fallback；可选 DashScope listwise reranker 只返回已有 chunkId 的完整排列，非法输出自动回退。
- Provenance：Rerank 前后完整保留 source、section、metadata 与 `KB[documentId/chunkId]` citation。

## Replay, Evaluation, and Grounding

WavePilot 的结论只来自通过结构校验和 SHA-256 校验的 Artifact。Replay 使用相同 Spec 与随机种子创建独立 Job，并按声明容差比较数值结果。

Retrieval Eval 数据集 `wavepilot-bilingual-retrieval-v2` 包含 80 个查询，每类 20 个：`THEORY`、`PARAMETER`、`TROUBLESHOOTING`、`EXPERIMENT_GUIDANCE`。它覆盖中英文、混合语言、缩写、同义改写、低字面重合、多 relevant chunk、跨 section、hard negative 和显式 metadata filter。

以下质量指标由 `RetrievalEvaluationReportTest` 的实际离线运行生成；离线 Dense 是确定性软件 embedding，不是通用语义模型 benchmark。Model Rerank 行在没有 provider 配置时明确使用 deterministic fallback。

| Strategy | R@1 | R@3 | R@5 | P@3 | P@5 | MRR | nDCG@5 | Citation | Hard-neg reject |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Dense Only | 0.262500 | 0.406250 | 0.531250 | 0.150000 | 0.117500 | 0.378333 | 0.402777 | 0.562500 | 0.787500 |
| BM25 Only | 0.756250 | 0.885417 | 0.904167 | 0.333333 | 0.205000 | 0.856667 | 0.857722 | 0.937500 | 0.700000 |
| Hybrid RRF | 0.537500 | 0.756250 | 0.879167 | 0.279167 | 0.197500 | 0.700000 | 0.731431 | 0.912500 | 0.625000 |
| Hybrid + deterministic rerank | 0.743750 | 0.879167 | 0.904167 | 0.325000 | 0.205000 | 0.850000 | 0.853227 | 0.937500 | 0.662500 |
| Hybrid + model strategy (offline fallback) | 0.743750 | 0.879167 | 0.904167 | 0.325000 | 0.205000 | 0.850000 | 0.853227 | 0.937500 | 0.662500 |

这组结果显示：相对离线 Dense，Hybrid RRF 的 R@5 增加 `0.347917`；deterministic rerank 相对未 rerank Hybrid 的 R@1 增加 `0.206250`。BM25 在该领域小语料上仍略优于 Hybrid rerank，因此项目不声称 Hybrid 或模型 reranker 全面优于 BM25。真实模型未在默认 CI 中调用。

## Engineering Validation

```powershell
mvn -B clean test
mvn -B "-Dtest=RetrievalEvaluationReportTest" test
mvn -B "-Dtest=AgentRegressionEvaluationTest,ScientificAgentLoopTest,ExecutionLedgerRecoveryTest" test
```

| Check | Reproducible result |
|---|---:|
| Maven tests | 388 passed, 0 failures, 0 errors, 0 skipped |
| Retrieval cases | 80（20 per query type） |
| Retrieval strategies / case results | 5 / 400 |
| Agent regression | baseline 17/17; candidate 17/17 |
| Candidate retrieval quality / citation validity | 0.904167 / 0.937500 |
| Duplicate execution rate | 0 |
| Completed-ledger recovery | reused verified Artifact; no new Job |
| Ambiguous submission recovery | marked `UNCERTAIN`; no duplicate Job |

测试结果验证的是编排、安全边界、恢复、检索和证据链。Mock `averageAccuracy`、测试延迟与离线 embedding 指标不能用作科研性能结论。

## Real-Model Opt-In

默认测试完全离线。要单独评测真实 DashScope listwise reranker 与可选 Planner/Replanner，请先设置有效凭据并启动应用：

```powershell
$env:DASHSCOPE_API_KEY = "<your-key>"
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:WAVEPILOT_MODEL_RERANKER_ENABLED = "true"
$env:WAVEPILOT_RERANKER = "model"
$env:WAVEPILOT_SCIENTIFIC_PLANNER_MODE = "model"
$env:WAVEPILOT_SCIENTIFIC_REPLANNER_MODE = "model"
mvn spring-boot:run
```

随后调用 `POST /api/retrieval-evaluations/run`，并从 JSON/Markdown Artifact 核对 `rerankerUsed`、质量指标和 latency。真实模型结果只有在该命令实际运行后才能对外表述。

## Quick Start

需要 JDK 17+ 与 Maven 3.9+。默认 Mock Runner 不需要 API Key、Milvus 或 MATLAB。

```powershell
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:DASHSCOPE_API_KEY = "not-configured"
mvn -B clean test
mvn spring-boot:run
```

Docker 全栈（WavePilot + Milvus，Runner 仍默认 Mock）：

```powershell
docker compose up -d --build
```

真实本机 MATLAB 模式使用仓库内固定模板和参数文件，不允许模型拼接任意命令：

```powershell
$env:WAVEPILOT_RUNNER_TYPE = "local-matlab"
$env:MATLAB_EXECUTABLE = "C:\Program Files\MATLAB\R2025b\bin\matlab.exe"
mvn spring-boot:run
```

## Technical Stack

- Java 17、Spring Boot 3.2、Maven、JUnit 5
- Spring AI Alibaba / DashScope（可选）
- Milvus Java SDK（可选）与 Apache Lucene BM25
- 文件型原子 AgentRun checkpoint 与 Execution Ledger（Repository 接口可替换）
- 原生 HTML/CSS/JavaScript 工作台、SSE
- GitHub Actions Java 17 CI

## Architecture Docs

- [Architecture](src/docs/ARCHITECTURE.md)
- [Hybrid Retrieval](src/docs/HYBRID_RETRIEVAL.md)
- [Scientific Agent Loop](src/docs/SCIENTIFIC_AGENT_LOOP.md)
- [Durable Execution](src/docs/DURABLE_EXECUTION.md)
- [Evaluation Design](src/docs/EVAL_DESIGN.md)
- [Artifact Provenance](src/docs/ARTIFACT_PROVENANCE.md)
- [Replay Design](src/docs/REPLAY_DESIGN.md)
- [Security Boundaries](src/docs/SECURITY_BOUNDARIES.md)
- [API Examples](src/docs/API_EXAMPLES.http)
- [Experiment Type Extension](src/docs/EXPERIMENT_TYPE_EXTENSION_GUIDE.md)

## Current Boundaries

- 默认与 CI 不调用真实模型、Milvus 或 MATLAB。
- 文件型 Ledger 面向单实例部署；尚无 JDBC 事务、分布式 lease 或多实例 leader election。
- Lucene 索引为进程内可重建索引；重启后需从权威知识源重新 ingest。
- 普通 Replay/Eval 的运行态仍主要在内存；本轮持久化重点是 Scientific Agent 的核心执行恢复链路。
- 已完成 Ledger 只有在 Artifact path、size、SHA-256 和 validated 标记全部通过时才复用；无法确认的副作用保持 `UNCERTAIN`，不会被当成成功。
- Mock 与离线 embedding 结果不证明通信算法精度、收敛性、吞吐或模型能力。

## License

[Apache License 2.0](LICENSE)
