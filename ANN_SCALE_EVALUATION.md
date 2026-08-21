# ANN scale evaluation

## Result

The production HNSW search shape is fast but does not meet the required document-recall warning
threshold on the deterministic clustered synthetic workload. At 100k chunks, `250/500` document
Recall@50 is 0.4786; at 500k it is 0.1704. The prescribed fallback checks through `500/1000` improve
recall only to 0.6640 and 0.2700 respectively. Production defaults were not changed in the
evaluation branch.

The requested 1,000,000-chunk run was started and then explicitly cancelled by the repository owner
because its HNSW build would take too long. The PostgreSQL backend was cancelled, the incomplete
table was dropped, and no 1M value below is estimated or extrapolated.

## Reproducibility

| Field | Value |
|---|---|
| Date | 2026-08-21 |
| Scale-runner commit | `f052f7a` |
| PostgreSQL / pgvector | 17.8 / 0.8.1 |
| Mini-PC | Intel N150, 4 cores, 15 GiB RAM, Ubuntu kernel 6.8.0-137, Docker 29.7.2 |
| Model / split | N/A; deterministic synthetic vectors, scale split |
| Seed / dimension | 20260821 / 384 |
| Distribution | normalized finite vectors clustered around document centroids; 1–20 chunks/document, geometric p=0.20, mean near 5; noise sigma 0.075 |
| Index | HNSW `vector_cosine_ops`, pgvector production defaults |
| Query protocol | 10 warmup, 100 measured; exact full scan as ground truth; HNSW baseline `250/500` |

Each scale used a freshly created regular `evaluation_scale_chunks` table, binary `COPY`, `ANALYZE`,
HNSW build and a second `ANALYZE`. Exact runs locally disabled index, index-only and bitmap scans;
HNSW runs set `hnsw.ef_search`. Exact runtime was never included in HNSW latency. The runner dropped
the table after each completed scale.

## Load, build and footprint

| Chunks | Load | HNSW build | Table bytes | Index bytes |
|---:|---:|---:|---:|---:|
| 100,000 | 2.98 s | 153.01 s | 164,175,872 | 204,808,192 |
| 500,000 | 14.66 s | 1,782.19 s | 819,724,288 | 1,024,008,192 |
| 1,000,000 | cancelled | cancelled | not reported | not reported |

The index is about 1.25x the table size at both completed scales. Build time grew from 2.6 minutes to
29.7 minutes between 100k and 500k, much faster than the 5x row-count increase.

## Recall and latency

| Scale | chunk/ef | Chunk recall | Document Recall@50 | Distinct docs | Exact P50/P95 | HNSW P50/P95/P99 |
|---:|---:|---:|---:|---:|---:|---:|
| 100k | **250/500** | 0.4161 | **0.4786** | 216.04 | 36.3 / 37.1 ms | 23.1 / 25.6 / 26.5 ms |
| 100k | 250/750 | 0.5214 | 0.5856 | 218.70 | 36.1 / 37.1 ms | 32.9 / 35.4 / 38.7 ms |
| 100k | 500/750 | 0.4912 | 0.5856 | 419.26 | 36.7 / 39.6 ms | 34.3 / 36.8 / 39.8 ms |
| 100k | 500/1000 | 0.5732 | 0.6640 | 424.12 | 37.3 / 38.1 ms | 45.8 / 49.1 / 58.0 ms |
| 500k | **250/500** | 0.1292 | **0.1704** | 231.22 | 166.4 / 185.1 ms | 44.7 / 49.6 / 55.2 ms |
| 500k | 250/750 | 0.1746 | 0.2212 | 233.77 | 166.1 / 174.5 ms | 67.7 / 72.5 / 73.6 ms |
| 500k | 500/750 | 0.1640 | 0.2212 | 457.79 | 167.8 / 178.8 ms | 67.5 / 71.9 / 77.9 ms |
| 500k | 500/1000 | 0.2046 | 0.2700 | 461.05 | 164.9 / 178.8 ms | 87.2 / 93.7 / 95.7 ms |

Every completed baseline is below document Recall@50 0.98 and therefore carries the requested
warning. Increasing chunk limit without increasing `ef_search` was not tested; the invalid
`500/250` combination remains excluded. The fallback sequence stopped at the prescribed maximum
`500/1000`, still far below 0.98.

## Representative plans

Both saved scale JSON files contain full `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` objects. The
exact plan uses a parallel sequential scan, sort and gather merge and contains no HNSW index. The
HNSW plan uses an index scan named `evaluation_scale_chunks_embedding_hnsw_idx`. This confirms that
the recall difference is measured between the intended execution strategies rather than two planner
variants of the same strategy.

## Interpretation and recommendation

- `250/500` is acceptable on the 1,289-chunk meaningful corpus (document recall 1.0) but not on this
  100k/500k synthetic distribution. Small-corpus fidelity must not be generalized to scale.
- Raising only candidate/ef values through `500/1000` does not approach 0.98 and roughly doubles
  500k HNSW P50 latency. A later scale study should evaluate HNSW construction parameters, index
  structure or a different ANN strategy rather than silently increasing the production limits.
- Until that work is approved and passes a representative production corpus, do not claim 0.98 ANN
  fidelity at six-figure chunk cardinality. Keep production defaults unchanged in this branch.
- A 1M checkpoint remains unmeasured by explicit owner decision; it is not a blocker to documenting
  the demonstrated 100k/500k failure mode.
