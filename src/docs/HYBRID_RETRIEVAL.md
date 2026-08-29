# Hybrid Retrieval 设计

更新时间：2026-08-30

## 版本决策

仓库实际配置为 Milvus Server 2.5.10（`vector-database.yml`）与 Milvus Java SDK 2.6.10
（`pom.xml`）。两者的 Dense API 已由项目使用，但本次不把版本不一致条件下的原生
Sparse/BM25/Hybrid API 当成稳定生产契约。最终方案是：

```text
Query
  -> QueryRouter
  -> DenseRetriever -> Milvus / in-memory Dense Store (candidateK=20)
  -> SparseRetriever -> Apache Lucene 9.12.1 BM25 (candidateK=20)
  -> Reciprocal Rank Fusion (k=60)
  -> DocumentReranker (deterministic default / noop / optional model adapter)
  -> Top-K Evidence
```

没有引入 Elasticsearch。Lucene 依赖只解决本地 BM25 索引与打分问题。

## 数据与身份

`KnowledgeChunk` 保留稳定 `chunkId`、嵌套的 `documentId/title/experimentType/source`、
`section`、`content` 和 attributes metadata。Dense 与 Sparse 都返回同一个 chunkId；RRF
按 chunkId 汇合，不根据文本猜测对应关系。`KnowledgeSearchResult` 在融合和 Rerank 后仍保留：

- source；
- `KB[documentId/chunkId]` Citation；
- documentType / experimentType；
- section / metadata；
- retrievalMethod。

## QueryRouter

分类是确定性规则，不复制四套 Agent Prompt：

| QueryType | 默认 documentType filter | 作用 |
|---|---|---|
| THEORY | THEORY | 理论证据 |
| PARAMETER | MATLAB_GUIDE | 参数与范围 |
| TROUBLESHOOTING | FAILURE_CASE | 故障与失败案例 |
| EXPERIMENT_GUIDANCE | EXPERIMENT_RECIPE | 实验流程 |

请求显式 metadata filter 优先于推断。相同 filter 同时传入 Dense 与 Lucene；测试验证两路
排除语义一致。

## RRF 与 Rerank

实现严格使用：

```text
RRF(d) = sum(1 / (rrfK + rank_i(d)))
```

不对 Dense similarity 与 BM25 score 做线性加权。确定性 Reranker 只按 query term coverage
重新排列已融合候选，再用 RRF score 和 chunkId 稳定打破平局；它不改写 Evidence。`NoOp`
用于基线，`ModelBasedDocumentReranker` 是可选端口，默认离线路径不会调用外部模型。

## 配置

| 属性 | 默认值 |
|---|---:|
| `wavepilot.knowledge.hybrid.dense-candidate-k` | 20 |
| `wavepilot.knowledge.hybrid.sparse-candidate-k` | 20 |
| `wavepilot.knowledge.hybrid.result-top-k` | 5 |
| `wavepilot.knowledge.hybrid.rrf-k` | 60 |
| `wavepilot.knowledge.hybrid.reranker` | deterministic |

## 可复现评测

`wavepilot-hybrid-retrieval-v1` 有 8 个知识 chunk、6 个查询 Case。JSON 与 Markdown 报告
由同一实际结果生成并登记 Artifact。2026-08-30 的 Top-3 运行中，Dense、BM25、Hybrid
RRF、Hybrid RRF + Rerank 四路都得到 Recall=1.0、Precision=0.333333、MRR=1.0、
nDCG=1.0、Citation Hit Rate=1.0。

这是小型精确匹配软件夹具，四路打平。它验证 filter、顺序、指标与 provenance，没有证明
Hybrid 对开放域或真实科研语料更优。

## 当前限制

- Lucene 索引当前在进程内，文档 ingest 时同步更新；重启后需要从源知识重新 ingest/rebuild。
- 未启用 Milvus 原生 Sparse/Hybrid fallback；升级并对齐 Server/SDK 后可新增实现而不改变
  `SparseRetriever` / `HybridRetrievalService` 接口。
- 可选模型 Reranker 没有默认 provider 实现，离线和 CI 永远使用确定性实现。
