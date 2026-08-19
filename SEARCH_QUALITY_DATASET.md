# Search-quality benchmark dataset

## Purpose

This deterministic synthetic benchmark evaluates document retrieval for the Nevis WealthTech use
case. It is deliberately separate from client-company discovery and contains no personal or
production data. The machine-readable source is
[`search-evaluation/data/dataset.json`](search-evaluation/data/dataset.json).

## Corpus

The corpus contains 13 text documents, each attached to a distinct synthetic client through the
normal client/document API. It includes proof-of-address material (utility bill, electricity
statement and bank statement), identity documents (passport and national identity card), income
documents (payslip and tax return), investments, adviser notes and deliberately unrelated content.

The runner records the runtime UUID allocated to every document in an ignored run artifact. Thus
the static labels stay readable and deterministic while Java remains the source of truth for IDs,
embeddings, PostgreSQL FTS and ranking.

## Queries and splits

There are 20 queries: 14 in the tuning split and 6 in the holdout split. Both splits contain
positive queries and a negative expected-no-result query. The benchmark covers these categories:

| Category | Example |
|---|---|
| Exact lexical | `passport`, `utility bill` |
| Morphological | `residential addresses`, `customer identification` |
| Domain vocabulary | `proof of address`, `identity documentation` |
| Natural language | `where does the customer live` |
| Ambiguous | `address`, `income`, `investments` |
| Negative | `aircraft maintenance manual`, `marine engine repair` |

The tuning split selects retrieval strategy and parameters. The holdout remains uninspected while
selecting `minimumSimilarity`, candidate limit, RRF `k`, or optional retriever weights, and is run
only against the selected final configuration.

## Relevance labels

Judgments use a four-point scale:

| Grade | Meaning |
|---:|---|
| 3 | Highly relevant; an expected direct answer |
| 2 | Relevant; a useful answer to the query |
| 1 | Weakly related; should not normally be treated as a recall hit |
| 0 | Irrelevant; omitted labels are implicitly zero |

Queries can have multiple relevant documents. Recall, precision and MRR treat grades 2 and 3 as
relevant; NDCG retains all grades, including weakly related grade-1 material. Negative queries
have no positive judgments. Their primary behavior measures are zero-result rate and the reported
negative false-positive rate.

## Metric semantics

Metrics are macro-averaged across positive queries, except NDCG which includes every query with at
least one non-zero graded judgment. `Precision@10` divides hits by the number actually returned
within the first ten results; an empty result has precision zero. Negative queries are excluded
from recall, precision, MRR and NDCG because they have no relevant document. Java-provided total
latency is summarized separately as mean, P50 and P95.

## Limitations

This is a small, hand-authored benchmark. It represents target query behaviors rather than user
traffic, has no inter-annotator agreement process, and cannot establish statistical significance.
Conclusions are therefore evaluation-supported defaults for this corpus, not claims of
production-optimal ranking.
