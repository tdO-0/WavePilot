# WavePilot 架构升级最终实现报告

更新时间：2026-08-30

## 实际修改的架构

1. 在现有 `ExperimentSpec + ExperimentService + ExperimentRunner` 之上增加受控 Scientific Agent 状态闭环：Goal → Plan → Retrieve → Execute → Observe → Verify → Replan/Finish。
2. 增加文件型 `AgentRunRepository`、原子 checkpoint、executionId/idempotency、只读状态重试、timeout、Artifact 恢复验证和终态 Trace。
3. 保留 Milvus Dense，引入 Apache Lucene 9.12.1 BM25；QueryRouter、Dense/Sparse Retriever、RRF 与 DocumentReranker 分层，统一 chunkId 并保留 provenance。
4. 增加独立 Retrieval Eval 和 Agent Regression Eval；JSON/Markdown 与比较报告都来自实际运行。
5. 增加 ModelRouter，记录 deterministic/fallback/model route 与 reason；provider 未返回 token usage 时字段为 null。

## 依赖与 fallback

- Compose Milvus Server：2.5.10；Milvus Java SDK：2.6.10。
- 因 Server/SDK 未对齐，没有把原生 Sparse/Hybrid 当成稳定契约；fallback 为 Milvus Dense + 进程内 Lucene BM25，而不是 Elasticsearch。
- 新增 `lucene-core` 与 `lucene-analysis-common` 9.12.1，只用于 BM25 索引/分析。
- Rerank 默认 deterministic；可选模型端口存在但默认不调用外部模型。

## 安全与执行边界

- Planner 的计划只有 `ScientificCapability`，无代码/命令/Runner/脚本路径。
- 每次实验仍由 `ExperimentService` 再次调用 Java Validator，复用原 Runner 与 ResultValidator。
- Replanner 提案先受 per-parameter 范围与单次变化限制，再过 Java Validator。
- Verifier 检查 Artifact 齐全、validated、SHA-256/大小、summary metric Grounding 与 Goal 约束。
- iteration、experiment、model call、token、retry 和 timeout 都有硬预算；预算耗尽是明确终态。

## 最终自动化结果

`mvn -B test`：375 Tests、375 Success、0 Failures、0 Errors、0 Skipped，`BUILD SUCCESS`。

目标仓库升级前基线同一命令为 363/363 通过。新增 12 项测试后全量无 regression；原 Replay、Report、Template、Frontend、安全边界与 Experiment 测试均仍通过。

## Retrieval Eval 实际结果

数据集 `wavepilot-hybrid-retrieval-v1`，6 Case，Top-3：

| Strategy | Recall@3 | Precision@3 | MRR | nDCG@3 | Citation Hit Rate |
|---|---:|---:|---:|---:|---:|
| Dense Only | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |
| BM25 Only | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |
| Hybrid RRF | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |
| Hybrid RRF + Rerank | 1.000000 | 0.333333 | 1.000000 | 1.000000 | 1.000000 |

四路在小型精确匹配语料上打平，不能声称 Hybrid 提升效果。

## Scientific Agent / Recovery 实际结果

- Mock 阈值搜索：3 iterations、3 experiments、2 replans，最终 `SUCCEEDED`，grounded `averageAccuracy=0.898440`。
- Budget case：不可能目标在 1 iteration / 1 experiment 后进入 `BUDGET_EXHAUSTED`。
- 幂等 case：相同 execution key 两次创建只产生 1 个 ExperimentJob。
- Recovery case：Observe 后 checkpoint 恢复复用原 jobId、1 个 ExecutionRecord、1 个 Observation，ExperimentJob 总数不增加，最终 `SUCCEEDED`。
- Agent Regression：实际 AgentRun + Retrieval Eval + ReplayRecord 的 9/9 维度通过；相同记录的 Baseline/Candidate 比较无退化。

## 仍未实现

- 分布式锁、lease、跨实例 exactly-once；
- JDBC/H2 对 ExperimentJob、Replay、Eval、ArtifactRegistry 索引的整体事务恢复；
- Lucene 索引跨重启持久化/自动从 Milvus 全量重建；
- Milvus 原生 Sparse/Hybrid（待 Server/SDK 对齐后验证）；
- 默认可用的真实模型 Reranker 与真实 token/cost 采集；
- MATLAB MCP Runner、真实模型 External Eval、本次升级后的真实 MATLAB 科研 benchmark；
- Scientific Agent 专用前端面板（API 已实现，现有工作台未扩展该视图）。

## 可安全使用的表述

可以写：受控 Scientific Agent 闭环、Java Validator 不可绕过、Mock 离线自动化、Dense+Lucene BM25+RRF、Citation provenance、可恢复 AgentRun checkpoint、幂等测试、375 项测试通过，以及上述 6-Case 检索评测的精确数字并注明数据集范围。

不可以写：真实通信算法准确率 0.898440、Hybrid 优于 Dense/BM25、真实 MATLAB 科研性能、生产级分布式 exactly-once、真实模型成本降低、Milvus 原生 Hybrid 已验证。Mock/Eval/Replay 只证明软件链路与可复现性，不证明科研算法正确性或效果。
