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
 └─ local embedding + pgvector HNSW chunk search
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

`V4__add_document_chunks.sql` replaces the obsolete document vector with `document_chunks`. Each
row stores a chunk body and a `vector(384)` embedding, keyed by `(document_id, chunk_index)`, and an
HNSW cosine index supports nearest-neighbour retrieval. Deleting a document cascades to its chunks.
The migration deliberately does not backfill existing local data; changing the model requires a
complete chunk rebuild, and changing vector dimension also requires a Flyway schema migration.

When a document is created, `DocumentService` preserves the complete supplied content in
`documents`, but deterministically indexes paragraph-, sentence-, or token-bounded chunks. The title
is capped at 32 model tokens and every embedding input is capped at 240 model-compatible tokens:

```text
<title>

<chunk body>
```

Consecutive chunks contain up to 30 body tokens of overlap. Chunking and all embeddings complete
synchronously before the document and chunks are batch-persisted in one transaction, so failures
cannot leave a partially indexed document. Model metadata and token slicing are application ports;
the MiniLM adapter exposes its 384 dimensions, 510-token capability, and matching tokenizer.

## Ranking and limits

Semantic retrieval sets transaction-local `hnsw.ef_search=500`, asks the cosine HNSW index for the
nearest 250 chunks, filters at provisional similarity `0.30`, and collapses them by document using
the maximum chunk similarity. At most 50 semantic documents continue to RRF. HNSW is used because
chunking materially increases vector cardinality; IVFFlat and a separate vector database remain
out of scope.

The lexical branch remains full-document FTS and is bounded by the 50-document candidate limit.
Raw FTS and cosine scores are never added directly. Weighted RRF
combines ranks while giving literal lexical matches a modest calibrated preference:

```text
score(document) = lexical-weight / (rrf-k + lexical-rank)
                + vector-weight  / (rrf-k + vector-rank)
```

Missing branches contribute zero. The calibrated defaults are `minimum-similarity=0.30`,
`lexical-weight=1.25`, `vector-weight=1.0`, and `rrf-k=60`. The same document in both lists receives
both contributions and is emitted once. Remaining ties are ordered by creation time and UUID, and
the final document list is bounded by `nevis.search.max-results`.
If embedding/query vector retrieval fails during search, the service logs the failure and still
returns lexical results.

`minimum-similarity=0.30`, chunk candidate limit 250, and `ef_search=500` are initial chunk-search
baselines. Measurements made against one whole-document vector, including the old candidate-limit
and score-gap recommendations, do not transfer to query-to-chunk score distributions and must be
re-evaluated by the coordinated quality task.

## Runtime and tests

Docker Compose uses the pinned `pgvector/pgvector:0.8.1-pg17` image and exposes only the API port.
Testcontainers uses the same image for PostgreSQL integration tests. Unit tests cover query rules,
model-aware chunking, the local embedding relation and RRF. Integration tests cover
Flyway/pgvector/HNSW persistence, chunk collapse, late-content semantic retrieval, FTS, term
expansion and hybrid deduplication; the Python suite exercises the same behavior over HTTP.

The main trade-offs are synchronous multi-chunk embedding cost, the need to re-embed every chunk if
the model or tokenizer changes, and provisional semantic thresholds. A larger model context is an
upper bound rather than a reason to enlarge chunks automatically; chunk size, overlap and candidate
settings remain evaluation parameters while the PostgreSQL/HNSW/FTS/RRF architecture stays stable.
