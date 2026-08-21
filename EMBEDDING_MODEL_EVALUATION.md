# Embedding-model evaluation

## Result

`intfloat/e5-small-v2` is the evaluation winner at `240/32/30`, with threshold `0.825637` and no
score-gap filter. It passes every predeclared challenger gate against the selected MiniLM baseline
on the once-only holdout and materially improves ranking quality. BGE is not recommended because its
holdout Recall@10 is 0.0185 below MiniLM, outside the allowed 0.01 loss.

This is a production recommendation, not a production change. `main` still uses MiniLM, and no
winner branch was created.

## Reproducibility and model preparation

The runs used the environment and corpus recorded in `SEARCH_QUALITY_EVALUATION_CHUNKED.md`:
2026-08-21, tuning commit `f052f7a`, fixed-holdout commit `5c12009`, PostgreSQL 17.8, pgvector
0.8.1, Intel N150/15 GiB mini-PC, and corpus SHA-256 `8ebcbf…1824`. Every model was full precision,
384-dimensional and was the only embedding model instantiated for its fresh evaluation database.

- MiniLM embeds the raw query and raw `<title>\n\n<chunk>` passage.
- BGE adds `Represent this sentence for searching relevant passages: ` to queries and leaves
  passages unprefixed.
- E5 adds `query: ` and `passage: ` respectively.

Each adapter counts the actual prepared string with its bundled tokenizer and enforces 510 tokens.
Java tests cover routing, prefixes, capabilities, Unicode token slicing and finite 384-d vectors.

## Fair 240-token comparison on tuning

All three models used `240/32/30`, `50/250/500`, RRF `60/1.25/1.0`, exact retrieval for quality,
separately calibrated quantile thresholds, and a fresh database.

| Model | Threshold | Recall@10 | MRR | NDCG@10 | Precision@10 | Negative FP | P50 / P95 total | Index 862 chunks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| MiniLM | 0.404236 | 0.8571 | 0.7558 | 0.7705 | 0.2884 | 0.1429 | 15 / 21.9 ms | 38.7 s |
| BGE small en v1.5 | 0.668941 | 0.9444 | 0.9291 | 0.9249 | 0.4153 | 0.0952 | 90 / 108.9 ms | 109.1 s |
| E5 small v2 | 0.825637 | **0.9683** | **0.9507** | **0.9511** | 0.1907 | 0.2857 | 87 / 102 ms | 110.1 s |

All three HNSW baseline runs had raw chunk recall, document Recall@50 and top-10 overlap 1.0000 on
the 862-chunk corpus. BGE has the best tuning precision/noise trade-off; E5 has the strongest recall
and ranking. BGE/E5 are materially slower than MiniLM on this four-core CPU and index at roughly
7.8 chunks/s versus 22.3 chunks/s for MiniLM at the same chunk size.

## Challenger optimization

Only nondominated BGE and E5 continued to the prescribed larger chunk configurations. Thresholds
were recalibrated on tuning for each fresh database.

| Model/config | Chunks | Threshold | Recall@10 | MRR | NDCG@10 | Precision@10 |
|---|---:|---:|---:|---:|---:|---:|
| **BGE 240/32/30** | 862 | 0.668941 | 0.9444 | 0.9291 | 0.9249 | 0.4153 |
| BGE 360/32/40 | 579 | 0.660796 | 0.9365 | 0.9078 | 0.9077 | 0.3300 |
| BGE 480/32/50 | 438 | 0.648771 | 0.9524 | 0.8125 | 0.8392 | 0.2202 |
| **E5 240/32/30** | 862 | 0.825637 | 0.9683 | 0.9507 | 0.9511 | 0.1907 |
| E5 360/32/40 | 579 | 0.827593 | 0.9444 | 0.9146 | 0.9171 | 0.2811 |
| E5 480/32/50 | 438 | 0.811755 | 0.9524 | 0.9092 | 0.9110 | 0.1134 |

The 240-token variants remained finalists. Larger chunks saved embeddings but lost ranking quality.

## Once-only holdout and gates

Final thresholds and chunk configurations were fixed before holdout. The runner executed one
threshold per model (`thresholdSelection=fixed-holdout`) and did not inspect holdout quantiles.

| Model/config | Recall@10 | MRR | NDCG@10 | Precision@10 | Hard-negative FP | P50 / P95 total |
|---|---:|---:|---:|---:|---:|---:|
| MiniLM 160/32/20 | 0.9815 | 0.9370 | 0.9431 | 0.1341 | 0.5556 | 34 / 47.5 ms |
| BGE 240/32/30 | 0.9630 | 0.9444 | 0.9493 | 0.3103 | 0.2222 | 92 / 122.5 ms |
| **E5 240/32/30** | **0.9815** | **1.0000** | **0.9921** | **0.2586** | **0.4444** | 91.5 / 113.3 ms |

Against MiniLM, E5 has no Recall@10 loss, gains 0.0630 MRR, 0.0490 NDCG@10 and 0.1245
Precision@10, improves hard-negative FP by 0.1111, and has document ANN recall 1.0000 on the
meaningful corpus. It therefore passes the allowed loss bounds and the required NDCG gain of at
least 0.01. E5 also ranks every holdout boundary category perfectly except the shared
duplicate/near-duplicate case (Recall@10 0.75); it fixes MiniLM's misleading-title ranking failure.

BGE improves ranking/noise but fails the Recall gate: 0.9630 is 0.0185 below MiniLM's 0.9815.

## Gap and operational trade-offs

E5's threshold still leaves hard-negative noise, so a fresh tuning-only gap grid was derived from
observed E5 top-two semantic score gaps. No nonzero candidate both improved hard-negative FP and
kept Recall/MRR/NDCG/Precision within their gates. Gap remains disabled; `0.003368` was not
transferred from the whole-document experiment.

E5's quality gain costs about 2.7x holdout P50 latency and 2.1x selected-config indexing time versus
MiniLM on this mini-PC. The recommendation is therefore E5 for measured search quality, with
MiniLM retained as the lower-latency operational fallback. Quantized models were out of scope.
