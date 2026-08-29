# Evaluation Design

WavePilot separates three evaluation layers so software correctness is not confused with communication-algorithm or model quality.

## Platform Offline Eval

The original 24 fixed cases exercise Spec parsing, missing/invalid parameter handling, controlled tool selection, tool security, Job state transitions, knowledge retrieval, Artifact citation, report grounding and Replay consistency. Default fixtures use scripted models and Mock Runner; platform validators and services are real.

Metrics are calculated from case outcomes rather than hardcoded: spec parse accuracy, missing-parameter detection, invalid-parameter blocking, tool selection, forbidden-tool blocking, Job submission, retrieval, Artifact citation, report grounding, Replay consistency and overall completion.

`POST /api/evaluations/compare` compares baseline and candidate on the same cases. Any passed-to-failed case or decreasing metric blocks release. The optional `external-eval` profile covers real-model parsing separately and must not be reported unless actually run.

## Retrieval Eval

Dataset: `wavepilot-bilingual-retrieval-v2`.

- 80 queries: 20 `THEORY`, 20 `PARAMETER`, 20 `TROUBLESHOOTING`, 20 `EXPERIMENT_GUIDANCE`.
- 46 chunks: 42 relevant domain chunks and 4 high-overlap semantic hard negatives.
- Chinese, English and mixed-language text.
- Acronyms and identifiers including BPSK, AWGN, BER, BLER, SNR, Eb/N0, camelCase and snake_case.
- Synonyms and low lexical-overlap semantic queries.
- Multi-relevant, cross-section and explicit metadata-filter cases.

Each judgment stores id, query, queryType, relevant chunk/document IDs, hard-negative chunk IDs, documentType, experimentType, topK and whether the document filter is explicit.

The same dataset evaluates:

1. Dense Only;
2. BM25 Only;
3. Hybrid RRF;
4. Hybrid RRF + Deterministic Rerank;
5. Hybrid RRF + Model Rerank strategy.

In offline CI, strategy 5 records `model-fallback-deterministic`; it is not represented as a real model run. An opt-in configured provider uses the controlled listwise scorer.

Per case and aggregate metrics:

- Recall@1, @3, @5;
- Precision@3, @5;
- MRR;
- nDCG@3, @5;
- Citation Hit Rate;
- Hard Negative Rejection Rate;
- total and rerank latency.

Outputs are `retrieval-eval.json`, `retrieval-eval.md`, and `retrieval-eval-comparison.json`. Comparisons include Dense → Hybrid, Hybrid → deterministic rerank and deterministic → model strategy deltas with an explicit measured/no-measured-improvement interpretation.

```powershell
mvn -B "-Dtest=RetrievalEvaluationReportTest" test
```

The reproducible quality table is in the README. Latency is emitted by every run but is environment-sensitive and should be compared only on controlled hardware.

## Agent Regression Eval

`AgentRegressionEvaluationService` consumes actual `AgentRun`, Retrieval Eval and `ReplayRecord` objects. It evaluates 17 dimensions:

- task success;
- plan validity;
- tool-selection correctness;
- final ExperimentSpec validity;
- citation validity;
- retrieval quality;
- grounded-result consistency;
- Replay success;
- loop termination;
- invalid tool-call rate;
- invalid ExperimentSpec rate;
- replan success rate;
- Artifact grounding success;
- recovery success;
- duplicate execution rate;
- total latency within budget;
- model calls within budget.

Telemetry preserves numeric rates and values instead of only booleans. Baseline uses Dense retrieval with deterministic Planner/Reranker. Candidate uses Hybrid/model-rerank strategy, the improved Router and any explicitly enabled model components. Default CI has zero model calls.

The fixed offline result is baseline 17/17 and candidate 17/17. Candidate retrieval R@5 is `0.904167` versus baseline Dense `0.531250`; citation validity is `0.937500` versus `0.562500`. This is a software regression comparison over the fixed retrieval fixture, not a model or scientific benchmark.

## Grounding and Recovery Metrics

- Citation validity requires retrieved citations plus validated/hash-bearing Artifact snapshots; the raw citation hit rate remains visible.
- Replan success counts accepted proposals that pass Java validation.
- Recovery success is one when every attempted reused execution reaches verified completion; no-attempt runs are neutral and report attempts in evidence.
- Duplicate rate uses distinct execution IDs plus explicit duplicate trace events.
- Model calls and token counts come from routing/provider data; missing provider usage is not estimated.

## APIs

- `POST /api/evaluations/run`
- `POST /api/evaluations/compare`
- `POST /api/retrieval-evaluations/run`
- `GET /api/retrieval-evaluations/{evaluationId}`
- `GET /api/retrieval-evaluations/{evaluationId}/report.md`
- `POST /api/agent-regression-evaluations/run`
- `POST /api/agent-regression-evaluations/compare`

## Interpretation Rules

- Never report Mock accuracy as MATLAB or communication-algorithm accuracy.
- Never report the offline model-rerank strategy as a real model result when `rerankerUsed` says fallback.
- Do not claim Hybrid is universally superior: on the fixed dataset BM25 is slightly stronger than Hybrid rerank on several ranking metrics.
- Do not compare latency from different machines as a causal improvement.
- A real-model number requires the opt-in command, provider configuration and retained generated Artifact.
