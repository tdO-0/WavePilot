# Durable Execution 设计

更新时间：2026-08-30

## Checkpoint 模型

`AgentRun` 持久化：runId、goal、currentSpec、currentPlan、currentStep、completedSteps、
ExecutionRecord、Observation、VerificationResult、ReplanDecision、retry/replan/iteration/experiment
计数、状态、时间戳和 AgentRunTrace。

`FileAgentRunRepository` 适配当前单体：

- 默认目录 `data/wavepilot/agent-runs`；
- 只允许 `RUN-XXXXXXXX-XXX`；
- JSON 临时文件完成后原子替换；
- Repository 接口可替换为 JDBC；
- 每个阶段转换前后 checkpoint。

## 幂等与恢复规则

每个实验执行使用稳定 `EXEC-{run}-I{iteration}` executionId/idempotencyKey。
`ExperimentService.create(spec, key)` 在同一运行实例内对重复 key 返回原 ExperimentJob。

恢复决策：

| checkpoint 状态 | 行为 |
|---|---|
| 没有 execution record | 首次提交，先写 PENDING checkpoint |
| RUNNING 且有 jobId | 查询原 Job，不再次 submit |
| COMPLETED 且已有 Observation | 校验 Artifact 后直接 Verify |
| Artifact hash/size 不一致 | Grounding 失败，不复用 |
| status 查询瞬时失败 | 只重试只读查询，受 maxRetries 限制 |
| timeout | 取消已知 Job，进入 TIMED_OUT |

任何副作用前先 checkpoint；拿到 jobId 后立即再次 checkpoint。副作用失败不会因存在部分
文件而被提升为成功。

## Trace

每次 AgentRun 记录 planning/retrieval/rerank/experiment/verification latency、Dense/Sparse
候选数、model route/reason、真实可得时的 token usage、replan count、total latency 和终态。
终态时 trace 同时保存在 AgentRun checkpoint 和 `AGENT_RUN_TRACE` Artifact。

## 自动化恢复结果

测试把一个已完成 Observe、尚未 Verify 的 AgentRun 改回 `VERIFYING` checkpoint，再调用
resume。恢复后复用相同 jobId、1 个 ExecutionRecord 和 1 个 Observation，ExperimentJob
总数没有增加，最终重新进入 `SUCCEEDED`。

## 当前限制

- File repository 是单机实现，没有分布式锁、lease 或多实例并发恢复。
- ExperimentJob 与 ArtifactRegistry 的索引本身仍主要在内存；AgentRun 能恢复已 checkpoint
  的 Observation 并通过落盘 Artifact hash 验证，但尚不能重建任意中断点的完整 Job 状态机。
- 进程在 Runner 已产生副作用但 jobId checkpoint 尚未落盘的极窄窗口，跨进程无法证明是否
  已执行；系统不会伪报成功。要消除此窗口，需要把 ExperimentJob/idempotency 映射迁移到
  同一事务型 JDBC 存储。
