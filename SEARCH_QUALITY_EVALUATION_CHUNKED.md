# Chunked search-quality evaluation

## Result

On MiniLM, `160/32/20` is the selected chunk configuration. It wins the tuning order specified for
this experiment (Recall@10, NDCG@10, MRR, boundary cases, then fewer chunks) by more than the 0.01
tie band, so the production baseline `240/32/30` is not retained as the quality winner. The selected
MiniLM threshold is `0.386544`. Candidate limits and RRF remain `50/250/500` and `60/1.25/1.0`.
These are evaluation findings only; production configuration was not changed.

The old whole-document `candidateLimit=10` and score-gap `0.003368` findings are historical. They
were neither copied into the chunked run nor treated as candidates.

## Reproducibility

| Field | Value |
|---|---|
| Date | 2026-08-21 |
| Tuning commit | `f052f7a` |
| Fixed-holdout runner commit | `5c12009` |
| Corpus | 72 deterministic English documents; 120 graded queries |
| Split | 84 tuning; 36 untouched holdout |
| Corpus SHA-256 | `8ebcbf4fb50dc7f5131d897e76ddb54a93bbd07e55a03aaa1d0365c45ddb1824` |
| PostgreSQL / pgvector | 17.8 / 0.8.1 |
| Model | full-precision `all-MiniLM-L6-v2`, 384 dimensions, safe input limit 510 |
| Mini-PC | Intel N150, 4 cores, 15 GiB RAM, Ubuntu kernel 6.8.0-137, Docker 29.7.2 |
| Retrieval / RRF | exact during tuning; HNSW grid separately; `50/250/500`; `60/1.25/1.0` |

The corpus contains 60 documents around 8–12 KB, 10 around 20–40 KB and two near the 50,000
character limit. Its annotations cover start/middle/end, both sides of chunk boundaries, oversized
paragraphs, token-fallback sentences, misleading titles, multi-topic material, duplicates and
multiple relevant chunks. Compact evidence is under `search-evaluation/results/2026-08-21`; raw
responses remain in the ignored mini-PC output directories.

## MiniLM chunk tuning

| Chunk / title / overlap | Chunks | Threshold | Recall@10 | MRR | NDCG@10 | Precision@10 | Tuning indexing |
|---|---:|---:|---:|---:|---:|---:|---:|
| **160/32/20** | 1,289 | **0.386544** | **0.9365** | **0.8773** | **0.8845** | 0.2028 | 52.1 s |
| 200/32/30 | 1,044 | 0.386558 | 0.8889 | 0.7891 | 0.8009 | 0.2206 | 44.3 s |
| 240/32/30 | 862 | 0.404236 | 0.8571 | 0.7558 | 0.7705 | 0.2884 | 38.7 s |

The 160-token configuration improves Recall@10 by 0.0476 over 200 and 0.0794 over 240. The
additional indexing cost is accepted for the evaluation recommendation because the requested
quality ordering puts chunk count last.

## Threshold selection

Threshold candidates were generated only from quantiles of permissive tuning scores, with at most
21 candidates. Relative to the permissive baseline, a candidate had to keep Recall@10 within 0.01
and MRR, NDCG@10 and Precision@10 within 0.02. Among admissible candidates the selector minimized
hard-negative false positives, then all negative false positives.

For MiniLM 160, `0.386544` preserved the permissive Recall@10, MRR and NDCG while reducing overall
negative FP from 1.0000 to 0.2857. Hard-negative FP remained 1.0000. A stricter candidate around
0.47 removed hard-negative noise but failed the recall gate. The fixed threshold was then applied
once to holdout, without deriving any holdout candidate:

| Split | Recall@10 | MRR | NDCG@10 | Precision@10 | Negative FP | Hard-negative FP | P50 / P95 total |
|---|---:|---:|---:|---:|---:|---:|---:|
| Tuning | 0.9365 | 0.8773 | 0.8845 | 0.2028 | 0.2857 | 1.0000 | 16 / 26 ms |
| Holdout | 0.9815 | 0.9370 | 0.9431 | 0.1341 | 0.5556 | 0.5556 | 34 / 47.5 ms |

Because thresholding did not eliminate no-result noise, a new gap grid was derived from E5 tuning
scores after model comparison. No candidate passed the same quality gates while improving hard-FP;
gap therefore remains disabled. The historical `0.003368` value was not evaluated or reused.

## Exact versus HNSW on the meaningful corpus

All eight allowed `(chunkLimit, efSearch)` pairs were evaluated. At only 1,289 chunks, every pair
had raw chunk Recall@K 1.0000, document Recall@50 1.0000 and top-10 overlap 1.0000.

| Chunk limit | ef_search values | Chunk recall | Document Recall@50 | Top-10 overlap |
|---:|---|---:|---:|---:|
| 100 | 250, 500, 750 | 1.0000 | 1.0000 | 1.0000 |
| 250 | 250, 500, 750 | 1.0000 | 1.0000 | 1.0000 |
| 500 | 500, 750 | 1.0000 | 1.0000 | 1.0000 |

The required baseline `250/500` returned 47.62 distinct documents on average, maximum 19.81 chunks
per document, concentration 0.0792, and semantic P50/P95 of 7.0/8.7 ms. The populated chunk table
was 2,932,736 bytes and its HNSW index 2,506,752 bytes. Integration tests additionally require exact
plans to exclude HNSW and meaningful-scale HNSW plans to include it.

## Boundary cases and compact regression

MiniLM holdout achieved Recall@10 1.0000 for start, middle, end, before/after boundary, oversized
paragraph, token fallback, misleading title, multi-topic and multiple-relevant-chunk tags. The one
exception was `duplicate_near_duplicate` at 0.75. Its misleading-title case was retrieved but ranked
poorly (MRR 0.20, NDCG 0.39), which became a useful model-comparison failure.

The historical 13-document/42-query suite was rerun after merging the chunk/HNSW production
baseline. Tuning hybrid Recall@10 was 0.9722, MRR 1.0000 and NDCG@10 0.9152, so the optional RRF
grid was not opened: neither MRR nor NDCG regressed by more than 0.02.

## Limitations

- The corpus is deterministic and graded but synthetic; it is not production traffic.
- Thresholds are model- and corpus-specific cosine cut-points, not relevance probabilities.
- Small-corpus ANN fidelity is not evidence for large cardinalities; see `ANN_SCALE_EVALUATION.md`.
- Full holdout was consumed exactly once per finalist and must not be reused for more tuning.
