# Nevis Search Service architecture

The service creates clients and their text documents, then exposes one global search endpoint:
`GET /search?q=...`. PostgreSQL is the source of truth and all schema changes are Flyway migrations.

## Hybrid document retrieval

Document retrieval has three complementary branches:

```text
normalized query
 ├─ PostgreSQL FTS + explicit search_term_mapping expansion
 └─ local embedding + pgvector cosine similarity
                 ↓
        Reciprocal Rank Fusion (RRF)
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
also meet `minimum-similarity`. Raw FTS and cosine scores are never added directly. Instead RRF
combines ranks:

```text
score(document) = Σ 1 / (rrf-k + rank)
```

The same document in both lists receives both contributions and is emitted once. Ties are ordered
by creation time and UUID, and the final document list is bounded by `nevis.search.max-results`.
If embedding/query vector retrieval fails during search, the service logs the failure and still
returns lexical results.

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
