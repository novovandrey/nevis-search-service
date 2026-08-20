# Search-quality evaluation

## Result

Hybrid PostgreSQL FTS plus semantic retrieval and weighted RRF remains the selected architecture.
The benchmark supports one production default change:

```text
minimumSimilarity = 0.30
candidateLimit    = 10
RRF k             = 60
lexical weight    = 1.25
vector weight     = 1.0
```

These are evaluation-supported defaults for this small corpus, not claims of production-optimal
parameters. `candidateLimit` changed from 50 to 10 because quality was unchanged on both tuning and
holdout while the lower bound is simpler and no slower. The other existing defaults remain because
the experiment found no safer improvement.

## Method

The 2026-08-20 run used an isolated Docker Compose project on the mini PC, with PostgreSQL
`pgvector/pgvector:0.8.1-pg17`, the packaged `all-MiniLM-L6-v2` model and the Java `evaluation`
profile. Its application was exposed on port 19090 because ports 8081 and 18081 were already in use;
the normal service on port 8080 and its `main` worktree were untouched.

The Python runner created 13 synthetic documents through the normal API, then called only
`POST /internal/evaluation/search` for every diagnostic search. It evaluated 14 tuning and 6
holdout queries from the checked-in graded dataset. 

Recall, precision and MRR treat relevance grades 2 and 3 as relevant; NDCG uses all grades. See
`SEARCH_QUALITY_DATASET.md` for the benchmark and metric definitions.

## Measurements

### Retrieval strategy (tuning)

| Mode | Recall@10 | Precision@10 | MRR | NDCG@10 | Negative false-positive rate |
|---|---:|---:|---:|---:|---:|
| FTS | 0.569 | 0.625 | 0.625 | 0.518 | 0.000 |
| Semantic | 0.917 | 0.752 | 1.000 | 0.886 | 1.000 |
| Hybrid | 0.972 | 0.731 | 1.000 | 0.915 | 1.000 |

Hybrid recovers lexical gaps such as `customer identification`, `where does the customer live` and
`document proving who the customer is`. FTS correctly returns no result for the two unrelated
queries, while semantic retrieval returns `Vehicle Service Record` for both. Hybrid inherits this
semantic no-result weakness at the recall-preserving threshold.

### Similarity and threshold

Relevant returned semantic candidates ranged from 0.223 to 0.658 (mean 0.428). Irrelevant returned
candidates overlapped them substantially, from -0.107 to 0.487 (mean 0.122); negative-query scores
reached 0.410. Similarity is therefore model- and corpus-specific, not a relevance percentage.

| Minimum similarity | Recall@10 | Precision@10 | NDCG@10 | Negative false-positive rate |
|---:|---:|---:|---:|---:|
| 0.10 | 1.000 | 0.270 | 0.932 | 1.000 |
| 0.15 | 1.000 | 0.364 | 0.932 | 1.000 |
| 0.20 | 1.000 | 0.507 | 0.924 | 1.000 |
| 0.25 | 0.972 | 0.609 | 0.915 | 1.000 |
| **0.30** | **0.972** | **0.731** | **0.915** | **1.000** |
| 0.40 | 0.806 | 0.764 | 0.774 | 0.500 |
| 0.45 | 0.736 | 0.847 | 0.713 | 0.000 |
| 0.50 | 0.611 | 0.708 | 0.603 | 0.000 |

Increasing the threshold does reduce noise, but 0.40 removes all relevant documents for the
natural-language residence query; 0.45 also loses identity and proof-of-address results. Keeping
0.30 accepts the known no-result limitation in order not to sacrifice core document recall.

### Candidate limit, RRF and weights

At threshold 0.30, limits 10, 20, 50, 100 and 200 all produced the same tuning Recall@10 (0.972),
Precision@10 (0.731), MRR (1.000) and NDCG@10 (0.915). Ten is the smallest saturation point. RRF
values 10, 20, 40, 60 and 100 also tied, so 60 remains the established configuration.

The optional vector-heavier `1.0:1.25` weighting raised tuning NDCG@10 from 0.915 to 0.928, but its
holdout MRR fell from 1.000 to 0.867 and NDCG@10 from 0.911 to 0.866. It was rejected rather than
overfitting the benchmark.

## Holdout validation and latency

| Configuration | Recall@10 | Precision@10 | MRR | NDCG@10 | Negative false-positive rate | P95 total latency |
|---|---:|---:|---:|---:|---:|---:|
| Previous defaults (50 candidates) | 0.933 | 0.617 | 1.000 | 0.911 | 0.000 | 8.25 ms |
| Final defaults (10 candidates) | 0.933 | 0.617 | 1.000 | 0.911 | 0.000 | 7.50 ms |
| Rejected vector-heavier weights | 0.933 | 0.617 | 0.867 | 0.866 | 0.000 | 5.00 ms |

The first cold baseline showed a higher P95 (23.8 ms); warmed hybrid runs were around 6–10 ms on
this small corpus. These timings are diagnostic observations, not load-test claims.

## Failure analysis and limits

- FTS misses semantic identity and residence paraphrases; semantic search restores them but can omit
  a secondary relevant result such as the bank statement for `where does the customer live`.
- At 0.30, both unrelated tuning queries returned `Vehicle Service Record`; the final holdout negative
  query did return no result. The small benchmark is insufficient to claim robust no-result behavior.
- The vector-heavier weighting demoted relevant `proof of residency` material to rank 3 on holdout.
- The benchmark is hand-authored and small. It has no statistical significance, user traffic or
  inter-annotator agreement. Document chunking, embedding model lifecycle and reindexing remain out
  of scope as described in `SEMANTIC_SEARCH_LIMITATIONS.md`.

## No-result / false-positive evaluation

### Method

On 2026-08-20 the evaluation worktree on the mini PC was updated to commit `1c9f958`, its
`nevis-evaluation` Docker volume was removed, and a fresh evaluation-profile service was started on
port 19090. The normal port-8080 service was not used. The runner created the 13-document synthetic
corpus through public endpoints, then called only the evaluation endpoint. Its ignored raw evidence
is retained at `/home/andrey/apps/nevis-evaluation/search-evaluation/output/latest/`.

The expanded suite contains 18 tuning negatives (4 easy, 7 domain, 7 hard) and 7 holdout negatives
(1 easy, 3 domain, 3 hard). A candidate must retain Recall@10 within 0.01 and MRR, NDCG@10 and
Precision@10 within 0.02 of the hybrid baseline. No Recall-only sensitivity result was needed.

### Baseline and failure pattern

| Tuning mode | Recall@10 | MRR | NDCG@10 | Overall FP | Easy FP | Domain FP | Hard FP | True no-result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| FTS | 0.569 | 0.625 | 0.518 | 0.000 | 0.000 | 0.000 | 0.000 | 1.000 |
| Semantic | 0.917 | 1.000 | 0.886 | 0.778 | 0.500 | 0.857 | 0.857 | 0.222 |
| Hybrid baseline | 0.972 | 1.000 | 0.915 | 0.778 | 0.500 | 0.857 | 0.857 | 0.222 |

Baseline mean / P50 / P95 total latency was 8.9 / 6.5 / 22.5 ms; warmed hybrid mode measured
5.9 / 6.0 / 7.5 ms. All negative baseline candidates were semantic-only, so retriever agreement
did not distinguish a reliable negative result in this corpus.

Hard-negative top scores (0.326–0.563) overlap positive relevant scores (0.325–0.658). The failure
taxonomy is identity-like requests returning `identity-card` or `passport`; financial statements
returning `bank-statement` or `risk-review`; employment returning `payslip`; and machinery queries
returning `vehicle-service`. Hard-negative mining records `payslip` for proof of employment,
`bank-statement` for mortgage repayment and credit-card statements, `identity-card` for child
identity verification and tax residency, and `risk-review` for investment performance. Employer
reference correctly returned no result.

### H8: global threshold

| Minimum similarity | Recall@10 | MRR | NDCG@10 | Overall FP | Hard FP |
|---:|---:|---:|---:|---:|---:|
| 0.30 | 0.972 | 1.000 | 0.915 | 0.778 | 0.857 |
| 0.35 | 0.847 | 0.917 | 0.800 | 0.611 | 0.714 |
| 0.40 | 0.806 | 0.917 | 0.774 | 0.444 | 0.571 |
| 0.45 | 0.736 | 0.917 | 0.713 | 0.333 | 0.571 |
| 0.50 | 0.611 | 0.750 | 0.603 | 0.222 | 0.429 |

No stricter global threshold passed the strict positive-quality gate. A single global threshold is
therefore insufficient for this corpus.

### H9/H10 and policy comparison

Raising the semantic-only threshold alone did not improve false positives while keeping the strict
gate. Score gaps did provide a narrow useful separator. The selected policy retains lexical-backed
results and semantic-only results only when the top semantic gap is at least `0.003368`; the value
was derived from tuning observations rather than set in advance. It post-filters Java's hybrid
ranking and does not recreate FTS, embeddings or RRF.

| Tuning strategy | Recall@10 | Precision@10 | MRR | NDCG@10 | Overall FP | Easy FP | Domain FP | Hard FP | True no-result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Hybrid baseline | 0.972 | 0.731 | 1.000 | 0.915 | 0.778 | 0.500 | 0.857 | 0.857 | 0.222 |
| Global threshold 0.35 | 0.847 | 0.681 | 0.917 | 0.800 | 0.611 | 0.500 | 0.571 | 0.714 | 0.389 |
| Score gap ≥ 0.003368 | 0.972 | 0.731 | 1.000 | 0.915 | 0.444 | 0.000 | 0.429 | 0.714 | 0.556 |

The gap rule improves overall FP by 33.3 percentage points and hard-negative FP by 14.3 points on
tuning without changing positive metrics. More aggressive gap or agreement rules reduce FP further
only by failing the gate.

### Holdout and conclusion

| Holdout configuration | Recall@10 | Precision@10 | MRR | NDCG@10 | Overall FP | Hard FP | True no-result | P95 latency |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Hybrid baseline | 0.933 | 0.617 | 1.000 | 0.911 | 0.571 | 0.667 | 0.429 | 5.45 ms |
| Score gap ≥ 0.003368 | 0.933 | 0.617 | 1.000 | 0.911 | 0.429 | 0.333 | 0.571 | 5.45 ms |

The improvement survived the held-out run: hard-negative FP fell from two of three to one of three,
with no positive holdout regression. A simple score-gap rejection policy is therefore
evaluation-supported as the next production candidate. It is not yet production behavior:
implementing it in Java requires a separate approved change and integration tests. The benchmark is
small and synthetic; remaining domain/hard false positives mean a second-stage reranker remains the
next hypothesis if this rule fails on a broader corpus.
