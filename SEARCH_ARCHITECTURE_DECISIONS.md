# Search architecture decisions

## Selected configuration

```text
retrieval          = PostgreSQL FTS + local MiniLM semantic retrieval + weighted RRF
minimumSimilarity  = 0.30
candidateLimit      = 10
RRF k               = 60
lexical/vector      = 1.25 / 1.0
```

## Decision record

| Decision | Alternatives evaluated | Evidence and trade-off |
|---|---|---|
| Hybrid retrieval | FTS-only, semantic-only | Hybrid had the best tuning Recall@10 (0.972) and NDCG@10 (0.915). It retains semantic no-result noise, which is documented rather than hidden. |
| Similarity threshold 0.30 | -1.0 to 0.50 | Higher thresholds suppress noise, but 0.40 and above materially reduce recall. Lower thresholds sharply reduce precision while still returning negative-query noise. |
| Candidate limit 10 | 10, 20, 50, 100, 200 | All candidates tied on tuning and 10 exactly matched previous defaults on holdout; select the smallest saturated value. |
| RRF k 60 | 10, 20, 40, 60, 100 | The grid tied on the benchmark. Preserve the existing value instead of claiming an unmeasured improvement. |
| Weights 1.25 / 1.0 | 1.0/1.0, 1.25/1.0, 1.0/1.25 | Vector-heavier weights raised tuning NDCG but regressed MRR and NDCG on holdout; retain literal-evidence preference. |

Revisit these defaults when the corpus grows materially, query behavior changes, the embedding model or
its input changes, a no-result policy is added, or production latency/quality telemetry becomes
available. See `SEARCH_QUALITY_EVALUATION.md` for the measurements and limitations.

## Pending no-result decision

No production no-result rule is adopted yet. The Python evaluation harness now compares global
threshold, lexical/semantic-agreement, score-gap and combined policies against an expanded negative
holdout. A policy may change this decision only after it passes the strict positive-quality gate and
does not materially regress holdout; otherwise the next justified research step is a second-stage
relevance/reranking model, not further arbitrary threshold tuning.
