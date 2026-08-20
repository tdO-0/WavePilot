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

## Controllers

| Controller | Responsibility |
|---|---|
| `WavePilotChatController` | ReactAgent chat and chat SSE |
| `ExperimentSpecParseController` | Natural-language Spec extraction and clarification |
| `KnowledgeController` | Communication document upload and filtered search |
| `ExperimentController` | Structured Spec validation and experiment lifecycle |

The application exposes only WavePilot controllers and controlled experiment workflows.

## Real, Mock and external behavior

- Real local implementation: Java validation, state machine, asynchronous orchestration, SSE, metadata/filter construction, Artifact hashing, Local MATLAB process control and deterministic result validation.
- Real MATLAB execution: MATLAB R2023b ran `polar-k-identification-simple-v1` and produced strictly validated CSV/MAT/PNG/summary/log artifacts.
- Algorithm boundary: `polar-bsc-binomial-k-baseline` performs real polar encoding and statistical K estimation but remains an unvalidated simplified baseline, not a paper reproduction or standardized algorithm.
- Integration fixture: the Phase 4 energy-threshold template is isolated as `polar-k-integration-fixture-v1`; it contains no polar encoding and is not a business result.
- Mock: the default runner still generates synthetic CSV/summary/log for offline platform tests.
- External: natural-language model calls require DashScope; production knowledge indexing/search requires Milvus.
- Offline tests: use Fake extraction models and an in-memory vector repository; no external services are contacted.
- Not implemented: MCP MATLAB Runner, MySQL persistence, real-model external Eval.

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
