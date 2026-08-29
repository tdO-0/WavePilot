# Scientific Agent Loop 设计

更新时间：2026-08-30

## 闭环

```text
Goal -> Plan -> Retrieve -> Execute -> Observe -> Verify -> Replan / Finish
```

`ExperimentGoal` 包含自然语言目标、初始 `ExperimentSpec`、目标 metric/operator/threshold、
`ParameterBounds` 和 `RunBudget`。`ScientificPlanner` 只产生注册能力：检索 Evidence、执行已
校验实验、验证 grounded 结果。计划没有代码、命令、Runner 或文件路径字段。

## 执行安全

`ScientificAgentService` 在每次执行前调用 `ExperimentService.parseAndValidate`，然后以
executionId 调用 `ExperimentService.create(spec, executionId)`。这条路径继续使用现有：

- `ExperimentSpecValidator`；
- `ExperimentStateMachine`；
- Mock / Local MATLAB `ExperimentRunner`；
- `ResultValidator`；
- `ArtifactRegistry`。

Planner 或 Replanner 的输出不能直接调用 Runner，也不能改变 MATLAB 模板白名单。

## Observe 与 Verify

Observation 只在 ExperimentJob 为 `SUCCEEDED` 后创建，包含 summary 的实际 metric 和所有
Artifact 的 id/type/relativePath/SHA-256/size/validated 快照。Verifier 确定性检查：

1. spec/plan/CSV/summary/log 是否齐全并已 validated；
2. 文件 SHA-256 与大小是否仍一致；
3. 目标 metric 是否来自 validated summary；
4. operator/threshold 是否满足。

Semantic model 不参与默认验证。`ModelRouter` 记录 route/reason；provider 没返回 usage 时，
token 字段保持 null，绝不估算成本。

## Replan 与终止

`BoundedScientificReplanner` 对每个数值参数应用 minimum、maximum、maximumChangePerReplan，
再把新 Spec 交给 Java Validator。以下任一条件会阻止无限循环：

- maxIterations；
- maxExperiments；
- maxModelCalls；
- maxTokens；
- wall-clock timeout；
- 参数到达边界或无法保持 Spec 合法；
- 明确 terminal state。

终态为 `SUCCEEDED / FAILED / BUDGET_EXHAUSTED / TIMED_OUT / CANCELLED`。

## 离线闭环实际结果

自动化案例从较高 BSC error-rate 区间开始搜索 `averageAccuracy >= 0.82`。默认 Mock Runner
实际执行 3 个 ExperimentJob，Replanner 实际修改区间 2 次；最后 grounded
`averageAccuracy=0.898440`，AgentRun 为 `SUCCEEDED`。这些数值是 Mock 软件夹具，只证明
Agent Loop 的控制流，不是极化码科研结果。

## API

- `POST /api/scientific-agent/runs`：创建并同步运行到终态；
- `POST /api/scientific-agent/runs/checkpoints`：只创建初始 checkpoint；
- `POST /api/scientific-agent/runs/{runId}/resume`：恢复；
- `GET /api/scientific-agent/runs/{runId}` / `GET /api/scientific-agent/runs`：查询。
