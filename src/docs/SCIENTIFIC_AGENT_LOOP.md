# Scientific Agent Loop

## Controlled Loop

```text
Goal -> Plan -> Retrieve -> Execute -> Observe -> Verify -> Replan / Finish
```

`ExperimentGoal` contains the natural-language objective, initial `ExperimentSpec`, target metric/operator/threshold, `ParameterBounds` and `RunBudget`. The loop reaches one of `SUCCEEDED`, `FAILED`, `BUDGET_EXHAUSTED`, `TIMED_OUT` or `CANCELLED`; it cannot continue without a bounded terminal condition.

## Planner Modes

Deterministic mode is the offline default. The optional `DashScopeScientificPlanModel` receives goal, current state, iteration, current Spec, evidence count and the registered capability set. It may select only:

- `RETRIEVE_EVIDENCE`;
- `EXECUTE_VALIDATED_EXPERIMENT`;
- `ANALYZE_RESULT`;
- `VERIFY_GROUNDED_RESULT`;
- `REPLAN_EXPERIMENT`.

Its DTO contains only a capability-name array. `ScientificPlanSchemaValidator` rejects unknown or duplicate capabilities, missing required steps and unsafe ordering. Java attaches the current `ExperimentSpec` to the execute step; a model never supplies Java, MATLAB, Shell, paths, executables or custom tools.

Planner model use is skipped when the model-call or token budget has no remaining capacity. Provider, parse or Schema failure is recorded in `AgentRunTrace` and falls back to the deterministic three-step plan.

Enable the optional planner with:

```powershell
$env:WAVEPILOT_SCIENTIFIC_PLANNER_MODE = "model"
```

## Execute, Observe, Verify

`ScientificAgentService` calls `ExperimentService.parseAndValidate` before a first submission and uses stable `executionId` as the idempotency key. The execution path retains the existing Java state machine, approved Runner selection, result validation and Artifact registry.

An Observation is built only from a `SUCCEEDED` Job or a completed Ledger entry whose Artifact references pass path, size and SHA-256 validation. It records summary values and Artifact id/type/path/hash/size/validated snapshots.

`ScientificVerifier` deterministically checks required Artifact presence, validated state, current hash/size, grounded metric origin and goal threshold. The optional semantic model does not authorize verification success.

## Semantic Replan

Deterministic mode adjusts only declared numeric parameters. Optional `DashScopeSemanticReplanModel` sees:

- `Observation`;
- latest `VerificationResult`;
- retrieved evidence;
- previous parameter changes;
- current Spec and `ParameterBounds`.

It proposes one complete `ExperimentSpec`. Java accepts it only if:

1. every changed parameter is explicitly registered in `ParameterBounds`;
2. no structural or unregistered field changed;
3. absolute bounds hold;
4. every change is within `maximumChangePerReplan`;
5. `ExperimentSpecValidator` accepts it;
6. iteration, experiment, model-call, token and time budgets remain.

Invalid semantic proposals are counted and fall back to deterministic bounded replan.

```powershell
$env:WAVEPILOT_SCIENTIFIC_REPLANNER_MODE = "model"
```

## Trace and Regression Metrics

`AgentRunTrace` records planning/retrieval/rerank/execution/verification latency, candidate counts, routing decisions, model calls, provider-reported tokens when available, invalid plan/spec/tool counts, recovered executions, duplicate executions, replans, terminal status and total latency.

Agent Regression Eval derives 17 dimensions from actual AgentRun, Retrieval Eval and Replay records. It reports rate telemetry for plan validity, loop termination, invalid calls/specs, replan success, Artifact grounding, recovery, duplicate execution, retrieval, citation, latency and model calls.

## Offline Fixture Result

The fixed Mock case reaches `SUCCEEDED` after three experiments and two bounded replans, with grounded fixture value `averageAccuracy=0.898440`. This validates loop control and grounding only; it is not a polar-code or MATLAB performance result.

## API

- `POST /api/scientific-agent/runs`
- `POST /api/scientific-agent/runs/checkpoints`
- `POST /api/scientific-agent/runs/{runId}/resume`
- `GET /api/scientific-agent/runs/{runId}`
- `GET /api/scientific-agent/runs`
