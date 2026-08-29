# Durable Execution

## Two Durable Records

`AgentRun` is the orchestration checkpoint. It stores goal, current Spec and plan, current/completed steps, execution records, observations, verifications, replans, retrieved evidence, counters, state, timestamps and trace.

`ExecutionLedgerEntry` is the side-effect record. It stores:

- `executionId` and `runId`;
- ExperimentJob `jobId` when known;
- canonical SHA-256 `ExperimentSpec` fingerprint;
- `PENDING / RUNNING / COMPLETED / FAILED / UNCERTAIN` status;
- durable Artifact references with type, relative path, SHA-256, size and validated flag;
- grounded summary values;
- retry count and start/completion timestamps;
- failure or uncertainty reason.

Both repositories are interfaces. The default single-node implementations use pretty JSON, safe IDs, normalized paths and temporary-file atomic replacement. The Ledger directory defaults to `data/wavepilot/execution-ledger`.

## Idempotency

Each Agent iteration derives a stable key:

```text
EXEC-{run-id-without-prefix}-I{iteration}
```

The same executionId must always map to the same canonical Spec fingerprint. A mismatch fails closed. Within a live process `ExperimentService.create(spec, executionId)` also returns the existing Job for a duplicate key.

## Write Order

```text
validate Spec
  -> persist Ledger PENDING
  -> persist AgentRun PENDING execution checkpoint
  -> submit Job with executionId
  -> persist Ledger RUNNING + jobId
  -> persist AgentRun RUNNING checkpoint
  -> await terminal state
  -> collect validated Artifact + summary
  -> persist Ledger COMPLETED
  -> persist AgentRun execution and Observation
```

The completed Ledger is written before the Observation checkpoint. A crash between those two writes can therefore reconstruct the Observation without submitting another Job.

## Recovery Matrix

| Durable state | Recovery behavior |
|---|---|
| `COMPLETED` + valid Artifact refs | verify path/size/hash/validated and rebuild execution + Observation; do not create a Job |
| `COMPLETED` + missing/tampered evidence | fail; completion is not trusted |
| `RUNNING` + known jobId and recoverable Job | continue polling the same Job |
| `RUNNING` + jobId absent from Job repository | change Ledger to `UNCERTAIN`; do not assume success |
| `PENDING` or `UNCERTAIN` without jobId | mark/retain `UNCERTAIN`; do not resubmit an ambiguous side effect |
| confirmed terminal failure/cancel | persist `FAILED`; do not create an Observation |
| read-only status query transient failure | retry within `maxRetries`; if unresolved, persist `UNCERTAIN` |

`UNCERTAIN` is intentional: at-least-once re-execution would be unsafe when the system cannot prove whether submission crossed the external side-effect boundary. An operator or a future transactional Job repository must reconcile it.

## Artifact Reuse

Artifact reuse does not trust the in-memory registry. `ArtifactRegistry.resolveVerifiedReference` resolves a relative path inside the configured Artifact root, rejects absolute paths and symlinks, and compares regular-file size and SHA-256 with the Ledger reference. Only entries already marked validated are eligible.

## Automated Recovery Coverage

Tests cover:

- file Ledger and Artifact verification after constructing fresh repository/registry instances;
- canonical Spec fingerprints independent of map insertion order;
- recovery from a completed Ledger before the Observation checkpoint;
- reuse of the original jobId and Artifact without increasing Job count;
- ambiguous `PENDING` submission marked `UNCERTAIN` without creating a Job;
- duplicate in-process execution key returning the same Job.

Reproduce with:

```powershell
mvn -B "-Dtest=ScientificAgentLoopTest,ExecutionLedgerRecoveryTest" test
```

## Limits

- The file implementation targets one application instance. It has no distributed lease, fencing token or cross-host transaction.
- The in-memory ExperimentService idempotency map is not itself durable. The Ledger closes completed-execution recovery and blocks ambiguous automatic replay, but it cannot reconcile an external side effect without a durable jobId.
- Full persistence of all Replay, Eval and ordinary Job runtime state remains outside this core Scientific Agent recovery scope.
- A production multi-instance deployment should implement the same repository interface with transactional JDBC and an idempotency uniqueness constraint.
