# WavePilot Architecture

## Runtime flow

```text
Natural language
  -> WavePilotChatController
  -> WavePilotChatService (Spring AI Alibaba ReactAgent)
  -> WavePilotAgentTools
     -> ExperimentSpecParser -> Spring AI ChatModel -> Jackson -> Java Validator
     -> KnowledgeService -> WavePilotKnowledgeRepository -> Milvus wavepilot_knowledge_v1
     -> ExperimentService -> ExperimentRunner
          -> MockExperimentRunner (default)
          -> LocalMatlabExperimentRunner (explicit opt-in)
               -> whitelisted fixed versioned MATLAB template
                  -> polar-k-identification-simple-v1 (default local template)
                  -> polar-k-integration-fixture-v1 (integration tests only)
               -> ProcessBuilder -> timeout/cancel/process-tree termination
               -> CSV/MAT/PNG/summary/run.log
          -> ResultValidator -> ArtifactRegistry
```

## Scientific Agent runtime

```text
ExperimentGoal + RunBudget + ParameterBounds
  -> ScientificPlanner (deterministic default / optional structured model plan)
  -> QueryRouter
       -> DenseRetriever (Milvus or in-memory)
       -> LuceneBm25SparseRetriever + CommunicationDomainAnalyzer
       -> Reciprocal Rank Fusion (rank-only)
       -> DocumentReranker (deterministic fallback / controlled listwise model)
  -> ExecutionLedger PENDING
  -> ExperimentService.create(spec, executionId)
       -> ExperimentSpecValidator
       -> existing ExperimentRunner and ResultValidator
  -> Observation (validated Artifact snapshots + grounded summary metrics)
  -> ScientificVerifier
       -> artifact completeness/hash/size
       -> goal constraint
       -> grounding
  -> BoundedScientificReplanner (optional semantic proposal + Java gates) or terminal AgentRunState
  -> ExecutionLedger COMPLETED + verified Artifact references
  -> FileAgentRunRepository checkpoint + AgentRunTrace artifact
```

`ScientificAgentService` is an orchestrator inside the existing monolith, not a new execution
service. It cannot call `ExperimentRunner` directly. Every side-effect step has an executionId
used as the `ExperimentService` idempotency key and as a durable Execution Ledger identity.
Completed Ledger entries rebuild missing Observations only after Artifact path/size/hash validation;
ambiguous submission windows become `UNCERTAIN` instead of being submitted twice. Side-effect-free
planning/retrieval/verification may be repeated during recovery.

## Hybrid retrieval runtime

The checked versions are Milvus Server `2.5.10` in `vector-database.yml` and Java SDK `2.6.10`
in `pom.xml`. To avoid treating version-misaligned sparse APIs as a stable contract, Milvus
remains the dense store and Apache Lucene 9.12.1 supplies BM25. `KnowledgeChunk.chunkId` is the
join key; title, section, experiment type, content, source and metadata remain attached to the
same evidence object through fusion/rerank. Router inference is a soft document-type boost; only
an explicit user filter excludes other types. The embedded Analyzer covers CJK bigrams, Latin
acronyms, digits, Eb/N0 and MATLAB-style identifiers.

## Trust boundaries

1. LLM output is untrusted. It must be deserialized and then accepted by `ExperimentSpecValidator`.
2. Agent tools never receive `ExperimentRunner`, `ExperimentJobRepository`, `ProcessBuilder`, `Path` or file-writing dependencies.
3. `submitExperiment` calls `ExperimentService.create`, which validates the spec again.
4. Only `ExperimentService` can read a validated summary or compare two succeeded experiments.
5. Milvus contains communication knowledge only. Jobs and Artifact metadata remain outside Milvus.
6. Mock remains the default. Local MATLAB is selected only by trusted host configuration; the Agent cannot choose the executable or runner.
7. `ProcessBuilder` receives a fixed `-batch` entrypoint. The user description and all `ExperimentSpec` fields are serialized to `matlab-input.json`, never interpolated into the command.
8. The runner copies one classpath-owned template selected from a two-entry Java whitelist into the isolated job directory and does not accept a user script path or MATLAB expression.
9. A zero MATLAB exit code is insufficient by itself: `ResultValidator` checks the complete CSV grid, numeric ranges, summary consistency, explicit `mock`, and MAT/PNG signatures before the job becomes `SUCCEEDED`.
10. Scientific plans contain only registered `ScientificCapability` values in a Java-validated order. Execution still passes through `ExperimentService.parseAndValidate/create`.
11. A semantic Replan may change only parameters registered in `ParameterBounds`; Java enforces absolute limits, per-step limits, Spec validation and remaining budgets.
12. A model reranker must return an exact permutation of candidate chunk IDs; it cannot create evidence, and invalid output falls back deterministically.
13. Recovery reuses a completed execution only after durable Artifact path/hash/size validation; unconfirmed side effects are `UNCERTAIN` and never reported as successful.

## Controllers

| Controller | Responsibility |
|---|---|
| `WavePilotChatController` | ReactAgent chat and chat SSE |
| `ExperimentSpecParseController` | Natural-language Spec extraction and clarification |
| `KnowledgeController` | Communication document upload and filtered search |
| `ExperimentController` | Structured Spec validation and experiment lifecycle |
| `ScientificAgentController` | create/checkpoint/resume/query durable Scientific Agent runs |
| `RetrievalEvaluationController` | execute and read reproducible hybrid retrieval reports |
| `AgentRegressionEvaluationController` | evaluate and compare actual Agent/RAG/Replay records |

The application exposes only WavePilot controllers and controlled experiment workflows.

## Real, Mock and external behavior

- Real local implementation: Java validation, state machine, asynchronous orchestration, SSE, metadata/filter construction, Artifact hashing, Local MATLAB process control and deterministic result validation.
- Real MATLAB execution: MATLAB R2023b ran `polar-k-identification-simple-v1` and produced strictly validated CSV/MAT/PNG/summary/log artifacts.
- Algorithm boundary: `polar-bsc-binomial-k-baseline` performs real polar encoding and statistical K estimation but remains an unvalidated simplified baseline, not a paper reproduction or standardized algorithm.
- Integration fixture: the Phase 4 energy-threshold template is isolated as `polar-k-integration-fixture-v1`; it contains no polar encoding and is not a business result.
- Mock: the default runner still generates synthetic CSV/summary/log for offline platform tests.
- External: natural-language model calls require DashScope; production knowledge indexing/search requires Milvus.
- Offline tests: use Fake extraction models and an in-memory vector repository; no external services are contacted.
- Not implemented: MCP MATLAB Runner, distributed/JDBC Ledger implementation, native Milvus sparse retrieval, a default-CI real-model Eval run.

## Phase 5 chain (5A-5E)

```text
SUCCEEDED job + validated artifacts
  -> ReportDataAssembler -> ExperimentReportData + ArtifactCitation
  -> ReportCitationValidator -> TemplateExperimentReportGenerator
  -> optional ControlledReportAgent (TEMPLATE_FALLBACK on boundary violation)
  -> ReplayService (Fingerprint -> independent job -> ReplayComparisonEvaluator -> REPRODUCIBLE)
  -> EvaluationService (24 fixed cases -> metrics from results -> Baseline/Candidate compare)
  -> static workbench (src/main/resources/static, REST + SSE only)
```

| Component | Responsibility |
|---|---|
| `ReportService` / `ReportController` | deterministic report, citation and validation API |
| `ArtifactController` | metadata/download/verify without absolute paths |
| `ReplayService` / `ReplayController` | independent reproducible re-runs with fingerprint and comparison |
| `EvaluationService` / `EvaluationController` | offline case execution, computed metrics, paired comparison |
| `WavePilotWorkbench` | static three-column demo UI with explicit boundary labels |
