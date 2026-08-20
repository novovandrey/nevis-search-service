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

## No-result decision

The 2026-08-20 isolated evaluation supports a **score-gap rejection policy as the next production
candidate**, not a production-default change yet. With the existing hybrid configuration, retain
lexical-backed results and retain semantic-only results only when their top semantic score exceeds
the second semantic score by at least `0.003368`.

This policy was selected on tuning because it preserved Recall@10 (0.972), MRR (1.000), NDCG@10
(0.915) and Precision@10 (0.731), while reducing overall negative FP from 0.778 to 0.444 and hard
negative FP from 0.857 to 0.714. It also held on the untouched holdout: positive metrics remained
0.933 / 1.000 / 0.911 / 0.617, overall FP fell from 0.571 to 0.429, and hard-negative FP fell from
0.667 to 0.333.

A global threshold of 0.35 or higher reduced false positives but violated the strict positive-quality
gate; lexical/semantic agreement supplied no additional signal because all returned negative
candidates were semantic-only. The policy must be revisited when the corpus, embedding model or query
mix changes, and it should be rejected in favour of a second-stage reranker if a broader benchmark
does not preserve this trade-off. See `SEARCH_QUALITY_EVALUATION.md` for raw evidence and limitations.
