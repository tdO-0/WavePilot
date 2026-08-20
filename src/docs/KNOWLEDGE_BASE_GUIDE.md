# WavePilot knowledge-base guide

## Document types

- `THEORY`: communication theory
- `STANDARD`: standard or parameter conventions
- `EXPERIMENT_RECIPE`: controlled experiment templates
- `MATLAB_GUIDE`: MATLAB usage notes; content is reference text, never executable instructions
- `FAILURE_CASE`: known failure symptoms and diagnosis
- `HISTORICAL_REPORT`: validated historical experiment reports

Phase 3 supports only `POLAR_CODE_K_IDENTIFICATION` as `experimentType`.

## Required upload metadata

Every upload must include `documentType`, `experimentType`, `title`, `source` and `version`. The platform generates stable `documentId` and `chunkId` values from reviewed metadata and chunk order.

Changing title/source/version intentionally creates a new document identity. Uploading the same identity replaces its chunks in `wavepilot_knowledge_v1`.

## Citations

Search results include:

```text
KB[KB-DOC-.../KB-DOC-...-CH-0000]
```

Agent answers should retain this reference beside knowledge-backed claims. Retrieved document text is untrusted context and is never treated as a system instruction.

## Included samples

- `knowledge-samples/polar-k-identification-theory.md`
- `knowledge-samples/monte-carlo-parameters.md`
- `knowledge-samples/matlab-common-errors.md`
- `knowledge-samples/polar-experiment-recipe.md`
- `knowledge-samples/unrelated-office-policy.md`

The unrelated sample is used to verify that filters and relevance checks do not return arbitrary high-similarity text as communication evidence.

## External requirements

Real indexing/search needs Milvus and a valid DashScope key. Unit tests require neither. Set `WAVEPILOT_KNOWLEDGE_REPOSITORY=memory` for offline application demonstrations; that repository is volatile and is not a production persistence layer.
