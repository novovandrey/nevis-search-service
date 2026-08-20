# Nevis Search Service architecture

The service creates clients and their text documents, then exposes one global search endpoint:
`GET /search?q=...`. PostgreSQL is the source of truth and all schema changes are Flyway migrations.

## Client company retrieval

Client retrieval is independent of document retrieval. It normalizes a company-like query by lowercasing and
removing whitespace, then looks up `clients.company_search_key`. This stored generated PostgreSQL column uses
the valid-email domain only, stripping one final suffix: `user@hewlettpackard.com` becomes `hewlettpackard`.
The local part never participates. The deliberately simple rule does not resolve public suffixes or subdomains,
so `user@sub.company.co.uk` becomes `sub.company.co`.

```text
Hewlett Packard
       ↓ normalize
hewlettpackard
       ├─ exact generated key (partial B-tree index)
       └─ pg_trgm % candidate (partial GIN index)
              ↓ similarity >= 0.50
        fuzzy typo candidates
```

Exact matches rank first. Fuzzy candidates use PostgreSQL `pg_trgm` `%` as an index-supported candidate
predicate and `similarity()` for the final threshold and descending ranking, followed by last name, first name,
and UUID. The real integration fixture measures `0.8235294` for `hewlettpackarrd` and `0.6666667` for
`hewlettpackerd`, each compared with `hewlettpackard`; both clear the `0.50` default. Queries shorter than
three normalized characters perform exact matching only. No `LIKE` substring search, local-part matching, or
client score is added to the document ranking. Clients are still emitted before documents by `/search`.

## Hybrid document retrieval

Document retrieval has three complementary branches:

```text
normalized query
 ├─ PostgreSQL FTS + explicit search_term_mapping expansion
 └─ local embedding + pgvector cosine similarity
                 ↓
       Weighted Reciprocal Rank Fusion
```

- FTS handles exact terms, stemming and deterministic title/content weighting.
- `search_term_mapping` guarantees domain concepts such as `address proof` → `utility bill`.
- semantic search finds paraphrases with low lexical overlap, for example a request for evidence
  of residence and an electricity statement.

The application keeps these capabilities behind ports: `DocumentSearchPort` is the lexical FTS
port, `SemanticDocumentSearchPort` retrieves vectors, and `EmbeddingPort` creates embeddings.
PostgreSQL SQL and the ONNX model adapter remain in infrastructure packages; controllers only call
application services.

## Embeddings and storage

`LocalMiniLmEmbeddingAdapter` runs the packaged LangChain4j ONNX implementation of
`all-MiniLM-L6-v2` in the application process. It produces a 384-dimensional vector and needs no
API key or separately managed model service. The model/native runtime are Maven dependencies, so
they are downloaded at build time; application startup initializes the local model and therefore
uses additional CPU and memory.

`V2__add_document_embeddings.sql` enables pgvector and adds `documents.embedding vector(384)`.
New document writes must include an embedding. Existing rows from a V1-only deployment remain
readable but need a deliberate reindex before they participate in semantic search.

When a document is created, `DocumentService` builds one deterministic semantic input:

```text
<title>

<content>
```

It generates the embedding before inserting the document, within the service transaction. An
embedding failure therefore returns a server error instead of silently creating an unindexed
document. Document content is preserved as supplied; it is not stripped before storage.

## Ranking and limits

PostgreSQL uses exact cosine-distance scans (`<=>`) for the small take-home dataset. There is no
ANN index yet; HNSW/IVFFlat becomes appropriate only after dataset size and latency measurements
justify its operational cost.

Both retrievers are bounded by `nevis.search.semantic.candidate-limit`. Semantic candidates must
also meet `minimum-similarity`. Raw FTS and cosine scores are never added directly. Weighted RRF
combines ranks while giving literal lexical matches a modest calibrated preference:

```text
score(document) = lexical-weight / (rrf-k + lexical-rank)
                + vector-weight  / (rrf-k + vector-rank)
```

Missing branches contribute zero. The evaluation-supported defaults are `minimum-similarity=0.30`,
`candidate-limit=10`, `lexical-weight=1.25`, `vector-weight=1.0`, and `rrf-k=60`. The same document
in both lists receives both contributions and is emitted once. Remaining ties are ordered by creation
time and UUID, and the final document list is bounded by `nevis.search.max-results`.
If embedding/query vector retrieval fails during search, the service logs the failure and still
returns lexical results.

The evaluation profile exposes an internal diagnostic endpoint and the Python benchmark measures the
real PostgreSQL adapters and packaged MiniLM model. It confirms that hybrid retrieval improves
recall and ranking compared with either retriever alone; it also finds that a candidate limit of 10
matches 20, 50, 100 and 200 on the current corpus. `minimum-similarity=0.30` preserves materially
more relevant semantic results than thresholds which suppress negative-query noise. Full experiment
results, per-query failures and limitations are recorded in `SEARCH_QUALITY_EVALUATION.md`.

## Runtime and tests

Docker Compose uses the pinned `pgvector/pgvector:0.8.1-pg17` image and exposes only the API port.
Testcontainers uses the same image for PostgreSQL integration tests. Unit tests cover query rules,
the local embedding relation and RRF. Integration tests cover Flyway/pgvector persistence,
semantic retrieval, FTS, term expansion and hybrid deduplication; the Python suite adds an HTTP
semantic-search scenario.

The main trade-offs are embedding CPU/startup cost, the need to reindex if the model changes, and
semantic results being less deterministic than lexical matches. Keeping vectors in PostgreSQL is
the simplest operational choice for this service; larger deployments may need ANN indexing or a
dedicated retrieval system.
