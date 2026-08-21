# Search evaluation final report

## Decision

Do not change the production embedding model automatically. The evaluation winner is
`intfloat/e5-small-v2` with `240/32/30` chunking, minimum similarity `0.825637`, document/chunk/HNSW
limits `50/250/500`, RRF `60/1.25/1.0`, and no score-gap filter. It passes every predeclared holdout
gate and is the exact configuration to consider in a separate production decision.

There is one important rollout condition: the current HNSW defaults do not provide acceptable ANN
fidelity on the deterministic 100k/500k synthetic workload. A six-figure chunk deployment should
not rely on the observed small-corpus recall of 1.0; it needs a separate index/ANN design study.

Production `main` remains on MiniLM with its existing `240/32/30`, `0.30`, `50/250/500` and
`60/1.25/1.0` baseline. No evaluation infrastructure or model change was merged into `main`.

## Reproducible run context

| Field | Quality/model runs | Scale runs |
|---|---|---|
| Date | 2026-08-21 | 2026-08-21 |
| Implementation commit | `f052f7a`; fixed holdout `5c12009`; gap tooling `3c4b52e` | `f052f7a` |
| PostgreSQL / pgvector | 17.8 / 0.8.1 | 17.8 / 0.8.1 |
| Mini-PC | Intel N150, 4 cores, 15 GiB RAM, Ubuntu kernel 6.8.0-137, Docker 29.7.2 | same |
| Corpus / split | SHA-256 `8ebcbf…1824`; 72 documents, 84 tuning + 36 once-only holdout queries | seed 20260821, clustered finite normalized 384-d vectors |
| Common parameters | documents 50, chunks 250, ef 500, RRF 60/1.25/1.0 | exact ground truth; HNSW 250/500; 10 warmup + 100 measured |

The checked-in aggregates and their SHA-256 manifest are in
`search-evaluation/results/2026-08-21`. Raw responses remain ignored under the mini-PC worktree's
`search-evaluation/output/<run-id>` paths.

## Quality conclusion

MiniLM chunk tuning selected `160/32/20`: its tuning Recall@10 0.9365 was 0.0476 above 200-token
chunks and 0.0794 above 240-token chunks. Its threshold `0.386544` passed the permissive-baseline
gates. The historical whole-document `candidateLimit=10` and gap `0.003368` were not transferred.

The three finalist configurations and thresholds were fixed before the holdout. Context for this
table is the quality/model column above; all rows use `50/250/500` and RRF `60/1.25/1.0`.

| Model/config/threshold | Recall@10 | MRR | NDCG@10 | Precision@10 | Hard-negative FP | P50/P95 total |
|---|---:|---:|---:|---:|---:|---:|
| MiniLM 160/32/20 / 0.386544 | 0.9815 | 0.9370 | 0.9431 | 0.1341 | 0.5556 | 34/47.5 ms |
| BGE 240/32/30 / 0.668941 | 0.9630 | 0.9444 | 0.9493 | 0.3103 | 0.2222 | 92/122.5 ms |
| **E5 240/32/30 / 0.825637** | **0.9815** | **1.0000** | **0.9921** | **0.2586** | **0.4444** | 91.5/113.3 ms |

E5 has no recall loss versus MiniLM and improves MRR by 0.0630, NDCG@10 by 0.0490, Precision@10
by 0.1245 and hard-negative FP by 0.1111. Its meaningful-corpus document ANN recall is 1.0, and the
NDCG gain exceeds the required 0.01. BGE fails because its recall loss is 0.0185, greater than the
allowed 0.01. E5 also resolves MiniLM's misleading-title ranking failure. Quantized models were not
part of this cycle.

Thresholding did not eliminate hard-negative noise. A new E5 gap grid was computed only from
tuning scores; no candidate both reduced hard FP and retained the quality gates. Gap remains off,
and the old `0.003368` was neither used nor retested.

## ANN conclusion

On the 1,289-chunk meaningful corpus, all eight valid `(chunkLimit, efSearch)` combinations had raw
chunk recall, document Recall@50 and top-10 overlap 1.0. The larger synthetic evidence is different.
Context for the following table is the scale column above; exact time is excluded from HNSW latency.

| Scale/config | Chunk recall | Document Recall@50 | Exact P50/P95 | HNSW P50/P95/P99 | Build | Table/index bytes |
|---|---:|---:|---:|---:|---:|---:|
| 100k / **250/500** | 0.4161 | **0.4786** | 36.3/37.1 ms | 23.1/25.6/26.5 ms | 153.0 s | 164,175,872 / 204,808,192 |
| 100k / 500/1000 | 0.5732 | 0.6640 | 37.3/38.1 ms | 45.8/49.1/58.0 ms | same index | same |
| 500k / **250/500** | 0.1292 | **0.1704** | 166.4/185.1 ms | 44.7/49.6/55.2 ms | 1,782.2 s | 819,724,288 / 1,024,008,192 |
| 500k / 500/1000 | 0.2046 | 0.2700 | 164.9/178.8 ms | 87.2/93.7/95.7 ms | same index | same |

Every scale baseline is below the 0.98 warning threshold, and the prescribed fallback sequence does
not recover it. Saved exact plans contain a parallel sequential scan and no HNSW index; saved HNSW
plans contain an index scan on `evaluation_scale_chunks_embedding_hnsw_idx`.

The 1M run was cancelled at the repository owner's request because of its long HNSW build. Its
Python process and PostgreSQL backend were stopped, the incomplete table was dropped, and no 1M
result is inferred.

## Verification

- Production prerequisite: `EmbeddingPort` now distinguishes query and passage at `main` commit
  `9fe1bc9`; MiniLM maps both to the same ONNX operation. The prerequisite passed local tests,
  mini-PC `mvn test` and `run-e2e.sh`, then was fast-forwarded with `git` and deployed without
  deleting the production volume.
- Evaluation branch: full mini-PC Maven suite passed 55/55, including Testcontainers, production
  HNSW/evaluation-HNSW equivalence and exact/HNSW plan assertions.
- Python suite passed 17/17 with `NEVIS_TEST_DSN`, including deterministic data/metrics and an actual
  binary-COPY round trip.
- Evaluation black-box HTTP checks passed, including a relevant phrase after the first 240-token
  window while returning the original complete document content.
- The compact 13-document/42-query regression retained hybrid Recall@10 0.9722, MRR 1.0000 and
  NDCG@10 0.9152. Because MRR/NDCG did not regress by more than 0.02, the optional RRF grid stayed
  closed.

## Final mini-PC state

| Service | Git / endpoint | State |
|---|---|---|
| Production | `main` at `9fe1bc9`, `http://192.168.1.87:8080` | app up; PostgreSQL healthy; `/v3/api-docs` 200; retained volume; V4 has HNSW chunks and no `documents.embedding` |
| Evaluation | `codex/search-quality-evaluation`, `http://192.168.1.87:18080`, DB `127.0.0.1:15432` | app up; PostgreSQL healthy; `/v3/api-docs` 200; E5/240/32/30/0.825637; 72 documents and 862 chunks |

Only the isolated evaluation volume was recreated. The final evaluation database contains the
selected full-precision long-document corpus and no `evaluation_scale_chunks` table. The production
volume was not removed; its pre-existing empty state remains 0 clients/documents/chunks.

## Exact production recommendation

1. Stop here, as required: keep production MiniLM and defaults unchanged until an explicit winner
   decision.
2. If quality is prioritized for the current small cardinality, approve a separate E5 production
   change using exactly `240/32/30`, threshold `0.825637`, `50/250/500`, RRF `60/1.25/1.0`, gap off.
3. Before targeting 100k or more chunks, run a separate ANN/index study on representative data. Do
   not claim document ANN recall 0.98 from the current HNSW defaults or merely raise ef to 1000.
4. Recalibrate thresholds after any corpus, model, quantization, tokenizer or index-strategy change.

Detailed evidence and limitations are in `SEARCH_QUALITY_EVALUATION_CHUNKED.md`,
`EMBEDDING_MODEL_EVALUATION.md` and `ANN_SCALE_EVALUATION.md`.
