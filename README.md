# Nevis Search Service

Java 25 / Spring Boot service for creating clients, storing their text documents, finding clients,
and searching documents with hybrid lexical and semantic retrieval.

The as-built design is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Run locally

Prerequisites:

- Docker with Docker Compose;
- or Java 25, Maven 3.6.3+, and a local PostgreSQL instance for running outside containers.

Start the complete service:

```bash
docker compose up --build
```

The API is available at `http://localhost:8080`. Flyway applies the schema and seed data
automatically. Swagger UI is at `http://localhost:8080/swagger-ui.html`, and the OpenAPI document is
at `http://localhost:8080/v3/api-docs`.

Stop the service with `docker compose down`. Add `--volumes` only when the local PostgreSQL data may
be deleted.

To run from Maven against an already running PostgreSQL database:

```bash
mvn spring-boot:run
```

The default connection is `jdbc:postgresql://localhost:5432/nevis` with username and password
`nevis`.

## Tests

```bash
mvn test
```

Pure application tests run directly. Database, vector-search, migration, isolation, and API
integration tests use a real pinned pgvector PostgreSQL image through Testcontainers; Docker must
be available for those tests. H2 is intentionally not used.

## Example workflow

Create a client:

```bash
curl -i -X POST http://localhost:8080/clients \
  -H "Content-Type: application/json" \
  -d '{
    "first_name": "Anton",
    "last_name": "Batiaev",
    "email": "anton.batiaev@neviswealth.com",
    "countryOfResidence": "UK"
  }'
```

The response is `201 Created` and includes the generated client `id`:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "first_name": "Anton",
  "last_name": "Batiaev",
  "email": "anton.batiaev@neviswealth.com",
  "countryOfResidence": "UK"
}
```

Use the returned `id` below as `CLIENT_ID`.

Create a text document belonging to that client:

```bash
curl -i -X POST http://localhost:8080/clients/CLIENT_ID/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Utility Bill",
    "content": "Electricity charges and the current residential address"
  }'
```

The response is `201 Created`:

```json
{
  "id": "22222222-2222-2222-2222-222222222222",
  "client_id": "11111111-1111-1111-1111-111111111111",
  "title": "Utility Bill",
  "content": "Electricity charges and the current residential address",
  "created_at": "2026-08-17T12:00:00Z"
}
```

Find clients by a company-like query derived from the email domain. Exact domains rank before
typo-tolerant matches:

```bash
curl "http://localhost:8080/search?q=Hewlett%20Packard"
```

The global endpoint returns one typed array. An exact `hewlettpackard.com` client precedes a
fuzzy `hewlettpackarrd.io` client, followed by document results in PostgreSQL FTS relevance order:

```json
[
  {
    "type": "CLIENT",
    "id": "00000000-0000-0000-0000-000000000000",
    "first_name": "Anton",
    "last_name": "Batiaev",
    "email": "anton.batiaev@neviswealth.com",
    "countryOfResidence": "UK"
  }
]
```

Search globally for the related business concept:

```bash
curl "http://localhost:8080/search?q=address%20proof"
```

The document containing the related term `utility bill` is returned:

```json
[
  {
    "type": "DOCUMENT",
    "id": "22222222-2222-2222-2222-222222222222",
    "client_id": "11111111-1111-1111-1111-111111111111",
    "title": "Utility Bill",
    "content": "Full document content...",
    "created_at": "2026-08-17T12:00:00Z"
  }
]
```

## API

### `POST /clients`

Creates a client. `first_name`, `last_name`, and a syntactically valid `email` are required;
`countryOfResidence` is optional. For backward compatibility, requests using `firstName` and
`lastName` are also accepted. Email is not assumed to be globally unique because the supplied
contract does not require that rule.

### `POST /clients/{id}/documents`

Creates a text document for an existing client. `title` and `content` are required. An unknown
client returns `404`; invalid input returns `400`.

### `GET /search?q={query}`

Searches clients by company derived from their email domain and documents across all clients.
Client lookup lowercases and removes query whitespace, then returns exact generated domain keys before
`pg_trgm` fuzzy matches at threshold `0.50`; it does not search first name, last name, local email parts,
or substrings. For example, `Hewlett Packard` normalizes to `hewlettpackard` and finds
`user@hewlettpackarrd.io`; exact `hewlettpackard.com` ranks first. Queries shorter than three normalized
characters use exact lookup only. A key removes only the final domain suffix, so
`user@sub.company.co.uk` becomes `sub.company.co`; public-suffix and subdomain resolution are intentionally
out of scope. Document results independently combine PostgreSQL FTS, deterministic business-term expansion,
and local semantic embeddings with weighted Reciprocal Rank Fusion. Results have a `type` discriminator
(`CLIENT` or `DOCUMENT`); embeddings and internal scores are never exposed. `content` is populated only for
`DOCUMENT` results and contains the complete stored document text without truncation, snippets, or highlighting.

Validation and failures use a consistent response shape:

```json
{
  "timestamp": "2026-01-01T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/clients",
  "violations": [
    {"field": "email", "message": "must be a well-formed email address"}
  ]
}
```

## Configuration

| Environment variable | Default | Purpose |
|---|---:|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/nevis` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `nevis` | Database username |
| `DB_PASSWORD` | `nevis` | Database password |
| `SERVER_PORT` | `8080` | HTTP port |
| `MAX_QUERY_LENGTH` | `255` | Maximum normalized query length |
| `MAX_SEARCH_RESULTS` | `50` | Maximum merged document results |
| `CLIENT_TRIGRAM_SIMILARITY_THRESHOLD` | `0.50` | Minimum `pg_trgm` similarity for fuzzy client-company matches |
| `SEMANTIC_CANDIDATE_LIMIT` | `10` | Maximum candidates from each retrieval branch |
| `SEMANTIC_RRF_K` | `60` | Reciprocal Rank Fusion constant |
| `SEMANTIC_MINIMUM_SIMILARITY` | `0.30` | Minimum cosine similarity for semantic candidates |
| `SEMANTIC_LEXICAL_WEIGHT` | `1.25` | Weighted RRF contribution from the lexical ranking |
| `SEMANTIC_VECTOR_WEIGHT` | `1.0` | Weighted RRF contribution from the semantic ranking |
| `MAX_DOCUMENT_CONTENT_LENGTH` | `1000000` | Maximum document content length |

## Architecture decisions

- PostgreSQL is the source of truth. A generated, weighted `tsvector` plus pgvector support hybrid
  document retrieval without a second datastore.
- `all-MiniLM-L6-v2` runs locally through ONNX and returns 384-dimensional embeddings. Its Maven
  dependencies are downloaded during the build; no external API key is required.
- CRUD repositories, `ClientSearchPort`, `DocumentSearchPort`, `SemanticDocumentSearchPort`,
  `EmbeddingPort`, and `QueryExpansionPort` are separate capabilities. PostgreSQL SQL, `tsquery`,
  vector operators, and mapping-table details stay in infrastructure adapters.
- Business expansion is explicit and deterministic. PostgreSQL FTS does not pretend that
  `address proof` and `utility bill` are linguistic synonyms.
- The small `search_term_mapping` table is Flyway-seeded and avoids hard-coding business vocabulary
  in application services.
- Document creation carries an explicit `clientId`; there is no ambient client context.
- The global `/search` facade is the only document-search API. It searches across all clients as
  required by the current cleanup plan.
- A shared database is appropriate for this implementation. Tenant, RLS, and database-per-client
  models are not introduced. Stronger isolation is a future product and operations trade-off.
- Raw lexical and cosine scores are not added directly. Weighted rank-based RRF gives the lexical
  ranking a `1.25:1.0` preference while still boosting results present in both branches.
- A reproducible benchmark evaluates the real Java implementation through an evaluation-profile-only
  endpoint. The current evidence-supported defaults are `minimumSimilarity=0.30`, `candidateLimit=10`,
  `rrfK=60`, and lexical/vector weights `1.25:1.0`; see
  [`SEARCH_QUALITY_EVALUATION.md`](SEARCH_QUALITY_EVALUATION.md) for results and limitations.
- Client company keys are a stored PostgreSQL generated column. Exact lookup uses a partial B-tree index;
  typo-tolerant lookup uses the partial `pg_trgm` GIN index with `%` as the candidate predicate and
  `similarity()` as the final threshold/ranking score. The measured test similarities against
  `hewlettpackard` are `0.8235294` for `hewlettpackarrd` and `0.6666667` for `hewlettpackerd`; both exceed
  the configured `0.50` threshold. Exact clients always rank before fuzzy clients, and client ordering does
  not affect document retrieval.
- Company-key extraction intentionally removes only the final suffix. Consequently,
  `user@sub.company.co.uk` produces `sub.company.co`; no public-suffix list or subdomain interpretation is
  introduced. The email local part and substring matching are deliberately excluded.
- A dedicated engine or ANN pgvector index becomes justified when search scale, latency, filters,
  analyzers, or operational requirements exceed exact PostgreSQL retrieval.

## Non-goals

Authentication, authorization, Tenant concepts, RLS, binary upload, PDF extraction, OCR, S3,
asynchronous ingestion, LLM-generated answers, and optional summarization are not part of this
implementation.
