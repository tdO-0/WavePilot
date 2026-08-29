# Engineering Validation Report

This document records reproducible software validation for the current architecture. It is not a communication-algorithm benchmark.

## Implemented Architecture

- 80-case bilingual Retrieval Eval with five strategies, full ranking metrics, hard-negative rejection, latency and generated baseline/candidate Artifacts.
- Communication-domain Lucene Analyzer for Chinese, English, acronyms, Eb/N0 and MATLAB identifiers.
- Soft Router inference and explicit-only hard metadata filtering.
- Controlled listwise model reranker with exact candidate-permutation validation and deterministic fallback.
- Optional structured model Planner and semantic Replanner behind Java capability, Schema, parameter and budget gates.
- File-backed Execution Ledger with canonical Spec fingerprints, durable verified Artifact references and fail-closed `UNCERTAIN` recovery semantics.
- 17-dimension Agent Regression Eval with numeric telemetry.

## Reproducible Commands

```powershell
mvn -B clean test
mvn -B "-Dtest=RetrievalEvaluationReportTest" test
mvn -B "-Dtest=AgentRegressionEvaluationTest,ScientificAgentLoopTest,ExecutionLedgerRecoveryTest" test
```

The full suite reports 388 passed, 0 failures, 0 errors and 0 skipped. The Retrieval Eval creates 400 case-strategy results from 80 cases. The quality table and interpretation are in the root README and generated Retrieval Markdown Artifact.

## Verified Recovery Behavior

- A completed Ledger entry with verified Artifact hash/size is reused without creating a second Job.
- A missing Observation can be rebuilt from the Ledger.
- The executionId and canonical Spec fingerprint must remain consistent.
- A `PENDING`/`RUNNING` side effect whose completion cannot be proved becomes `UNCERTAIN`; it is not automatically repeated or treated as success.

## Claims Excluded

- No real DashScope listwise reranker or Planner result is claimed by default CI.
- No Mock accuracy is a MATLAB, polar-code or scientific performance measurement.
- Hybrid does not universally outperform BM25 on the fixed dataset.
- The file Ledger is not a distributed transaction or multi-instance coordination system.
