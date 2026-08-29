# Hybrid Retrieval

## Pipeline

```text
Query
  -> QueryRouter
  -> DenseRetriever  -> Milvus / offline in-memory store
  -> SparseRetriever -> Apache Lucene BM25
  -> Reciprocal Rank Fusion by stable chunkId
  -> DocumentReranker
  -> Top-K evidence with provenance
```

Lucene remains an embedded sparse index; Elasticsearch is not required. Dense and sparse retrieval both return the authoritative `KnowledgeChunk` identity. Fusion and reranking preserve `chunkId`, `documentId`, source, section, metadata and `KB[documentId/chunkId]` citation.

## Communication Domain Analyzer

`CommunicationDomainAnalyzer` combines Lucene filters instead of introducing an external tokenizer service:

1. `PatternReplaceCharFilter` normalizes `Eb/N0` to searchable `EbN0` and emits camelCase components without losing the original identifier.
2. `StandardTokenizer` handles Latin words, numbers and identifiers.
3. `WordDelimiterGraphFilter` preserves originals and emits useful snake_case/word/number forms.
4. `CJKBigramFilter` creates searchable Chinese bigrams.
5. lowercase and ASCII folding normalize comparison.

Tests cover Chinese and English retrieval, mixed-language queries, intact `BPSK/AWGN/BER/BLER`, `Eb/N0`, `decodeFrame` and `noise_variance`.

## Routing Semantics

The Router is a deterministic intent classifier with four query types:

| QueryType | Primary document type |
|---|---|
| THEORY | THEORY |
| PARAMETER | MATLAB_GUIDE |
| TROUBLESHOOTING | FAILURE_CASE |
| EXPERIMENT_GUIDANCE | EXPERIMENT_RECIPE |

An explicit `KnowledgeSearchRequest.documentType` is a hard filter and is applied identically to Dense and BM25. An inferred type is only a configurable boost (`routing-boost`, default `1.08`); all other document types remain fallback candidates. A wrong Router guess therefore cannot remove the relevant document from the candidate set.

## RRF and Reranking

RRF uses ranks, not incomparable raw scores:

```text
RRF(d) = sum(1 / (rrfK + rank_i(d)))
```

Available strategies are Dense only, BM25 only, Hybrid RRF, Hybrid + deterministic rerank, and Hybrid + model rerank.

`DeterministicDocumentReranker` is the offline fallback. `DashScopeListwiseRerankModel` is opt-in and sends a bounded Query + candidate list containing only chunkId, title, section and truncated content. It must return strict JSON:

```json
{"chunkIds":["existing-id-2","existing-id-1"]}
```

`ModelBasedDocumentReranker` accepts only an exact permutation of every supplied candidate ID. Unknown, missing or duplicate IDs, invalid JSON and provider failures fall back to deterministic reranking. `RetrievalResponse.rerankerUsed` and rerank latency make fallback visible.

## Configuration

| Property | Default |
|---|---:|
| `wavepilot.knowledge.hybrid.dense-candidate-k` | 20 |
| `wavepilot.knowledge.hybrid.sparse-candidate-k` | 20 |
| `wavepilot.knowledge.hybrid.result-top-k` | 5 |
| `wavepilot.knowledge.hybrid.rrf-k` | 60 |
| `wavepilot.knowledge.hybrid.routing-boost` | 1.08 |
| `wavepilot.knowledge.hybrid.reranker` | deterministic |
| `wavepilot.knowledge.hybrid.model-reranker-enabled` | false |

## Retrieval Evaluation

`wavepilot-bilingual-retrieval-v2` contains 42 communication-engineering chunks plus 4 high-overlap hard negatives and 80 query judgments (20 per query type). Cases cover Chinese, English, mixed language, acronyms, synonyms, low lexical overlap, parameters, MATLAB failures, multiple relevant chunks, cross-section intent and explicit metadata filters.

Each run executes 400 case-strategy combinations and computes R@1/3/5, P@3/5, MRR, nDCG@3/5, Citation Hit Rate, Hard Negative Rejection Rate, total latency and rerank latency. It registers:

- `retrieval-eval.json`;
- `retrieval-eval.md`;
- `retrieval-eval-comparison.json`.

Reproduce with:

```powershell
mvn -B "-Dtest=RetrievalEvaluationReportTest" test
```

The measured quality table is kept in the project README. The offline model strategy reports deterministic fallback when no provider is configured. These numbers characterize this fixed software dataset and deterministic embedding only; they are not scientific or open-domain retrieval claims.

## Real Model Evaluation

```powershell
$env:DASHSCOPE_API_KEY = "<your-key>"
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:WAVEPILOT_MODEL_RERANKER_ENABLED = "true"
$env:WAVEPILOT_RERANKER = "model"
mvn spring-boot:run
```

Call `POST /api/retrieval-evaluations/run`, then inspect `rerankerUsed`, metrics and latency in the generated Artifact. No real-model result is claimed until this opt-in path is actually run.

## Limits

- The Lucene index is in-process and must be rebuilt from the authoritative source after restart.
- Current Server/SDK version differences mean Milvus native sparse/hybrid behavior is not treated as the project contract.
- Listwise reranking sends bounded candidate text to the configured provider; deployments must apply their own data-governance policy.
