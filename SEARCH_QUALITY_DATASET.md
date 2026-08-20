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

There are 42 queries: 30 in the tuning split and 12 in the holdout split. Both splits contain
positive queries and explicit expected-no-result queries. The benchmark covers these categories:

| Category | Example |
|---|---|
| Exact lexical | `passport`, `utility bill` |
| Morphological | `residential addresses`, `customer identification` |
| Domain vocabulary | `proof of address`, `identity documentation` |
| Natural language | `where does the customer live` |
| Ambiguous | `address`, `income`, `investments` |
| Easy negative | `aircraft maintenance manual`, `restaurant reservation` |
| Domain negative | `driver licence`, `mortgage agreement` |
| Hard negative | `proof of employment`, `mortgage repayment statement` |

The 25 negative queries are split 18 tuning / 7 holdout: 5 easy, 10 domain and 10 hard. Every
negative has an empty `judgments` object, which is the explicit product judgement that no document
in this corpus is relevant. It is not relabelled after observing an embedding match. The tuning
split selects a rejection policy; the negative holdout is reserved for final validation.

`insurance policy` is deliberately not a negative query because the corpus contains a life-insurance
policy. `vehicle registration certificate` is used instead as an absent, related domain document.

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
have no positive judgments. Their primary behavior measures are overall negative false-positive
rate, true no-result rate, and separate easy/domain/hard false-positive rates.

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
