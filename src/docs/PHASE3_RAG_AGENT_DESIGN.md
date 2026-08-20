# Phase 3 RAG and Agent design

## Natural-language Spec pipeline

```text
message
 -> ExperimentSpecExtractionModel
 -> Spring AI ChatModel / DashScope
 -> JSON extraction
 -> Jackson ModelCandidate
 -> missing-field analysis
 -> ExperimentSpec
 -> ExperimentSpecValidator
 -> COMPLETE | NEEDS_CLARIFICATION | INVALID
```

The extraction prompt requires null for missing fields. `sampleCount`, `monteCarloTimes`, code lengths and error-rate parameters are never inferred by Java. `randomSeed=20` and Mock-supported output types may be defaulted, and both defaults are explicitly returned in `defaultedFields` and `warnings`.

Malformed model JSON, an unavailable model and unrelated input become structured `INVALID` results instead of unhandled exceptions.

## Agent

`WavePilotChatService` creates a Spring AI Alibaba `ReactAgent`. Its method tools are the single `WavePilotAgentTools` component.

Tools:

1. `searchExperimentKnowledge`
2. `createExperimentSpec`
3. `validateExperimentSpec`
4. `createExperimentPlan`
5. `submitExperiment`
6. `getExperimentStatus`
7. `cancelExperiment`
8. `listExperimentArtifacts`
9. `readExperimentSummary`
10. `compareExperiments`

Every experiment tool delegates to `ExperimentSpecParser` or `ExperimentService`. The knowledge tool delegates to `KnowledgeService`. Tools do not directly access the Runner, Repository, job state or filesystem.

## Chat and embedding model boundary

The installed Spring AI Alibaba RC2 artifacts contain both `ChatModel` and `DashScopeEmbeddingModel`, and the Spring AI 1.1.0 API contains `EmbeddingModel`. Phase 3 therefore uses:

- `ChatModel` for structured extraction and ReactAgent;
- `WavePilotEmbeddingService` as the single application embedding port;
- Spring AI `EmbeddingModel` as its primary adapter;
- the existing `VectorEmbeddingService`/DashScope SDK only as a compatibility fallback if RC auto-configuration exposes no `EmbeddingModel` bean.

Failures are not retried through both implementations, so one user request cannot accidentally produce duplicate remote calls. API key and model names are centralized under `wavepilot.ai` and referenced by Spring AI and the legacy adapter.

`DashScopeConfig` now uses the JDK HTTP client request factory, removing the deprecated OkHttp Spring request factory.

## Mock disclosure

The system prompt and every task/artifact tool expose the current runner as Mock. `WavePilotChatService` also prepends a disclosure if a model response omits it. No Phase 3 component calls MATLAB, a shell or `ProcessBuilder`.
