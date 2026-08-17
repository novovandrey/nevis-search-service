# Nevis Search Service

Java 25 / Spring Boot service for creating clients, storing their text documents, finding clients,
and searching documents with PostgreSQL Full Text Search.

The as-built architecture is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). The original agreed
plan is preserved unchanged in [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md), and
the strict company-domain client-search rules are in
[`docs/CLIENT_SEARCH_PLAN.md`](docs/CLIENT_SEARCH_PLAN.md).

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

Pure application tests run directly. Database, search, migration, isolation, and API integration
tests use a real pinned PostgreSQL image through Testcontainers; Docker must be available for those
tests. H2 is intentionally not used.

## Example workflow

Create a client:

```bash
curl -i -X POST http://localhost:8080/clients \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Anton",
    "lastName": "Batiaev",
    "email": "anton.batiaev@neviswealth.com",
    "countryOfResidence": "UK"
  }'
```

The response is `201 Created` and includes the generated client `id`. Use it below as
`CLIENT_ID`.

Create a text document belonging to that client:

```bash
curl -i -X POST http://localhost:8080/clients/CLIENT_ID/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Utility Bill",
    "content": "Electricity charges and the current residential address"
  }'
```

Search only that client's documents with a related business term:

```bash
curl "http://localhost:8080/clients/CLIENT_ID/documents?q=address%20proof"
```

The utility-bill document is returned because the initial Flyway data places `address proof`,
`proof of address`, `proof of residency`, `utility bill`, and `bank statement` in the same explicit
business-term group. The SQL also contains `client_id = CLIENT_ID`, so a stronger match owned by a
different client cannot leak into this result.

Find the client by a company-like query derived from the email domain:

```bash
curl "http://localhost:8080/search?q=Nevis%20Wealth"
```

The global endpoint returns one typed array. Exact company-domain client matches come first in
deterministic name order, followed by document results in PostgreSQL FTS relevance order:

```json
[
  {
    "type": "CLIENT",
    "id": "00000000-0000-0000-0000-000000000000",
    "firstName": "Anton",
    "lastName": "Batiaev",
    "email": "anton.batiaev@neviswealth.com",
    "countryOfResidence": "UK"
  }
]
```

## API

### `POST /clients`

Creates a client. `firstName`, `lastName`, and a syntactically valid `email` are required. Email is
not assumed to be globally unique because the supplied contract does not require that rule.

### `POST /clients/{clientId}/documents`

Creates a text document for an existing client. `title` and `content` are required. An unknown
client returns `404`; invalid input returns `400`.

### `GET /clients/{clientId}/documents`

- Without `q`, lists only that client's documents.
- With `q`, normalizes and expands the query, then searches only that client's documents.
- Optional `limit` defaults to `20`; list requests also accept `offset`, defaulting to `0`.

### `GET /search?q={query}`

Searches clients by company derived from their email domain and searches documents across all
clients, as required by the assignment. Client lookup does not search first name, last name, or
full email. Results have a `type` discriminator (`CLIENT` or `DOCUMENT`). The optional per-type
`limit` defaults to `20`. Client and document relevance values are deliberately not exposed as one
fake cross-type score.

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
| `MAX_QUERY_LENGTH` | `200` | Maximum normalized query length |
| `SEARCH_DEFAULT_LIMIT` | `20` | Documented default result limit |
| `SEARCH_MAX_LIMIT` | `100` | Maximum result limit |
| `MAX_DOCUMENT_CONTENT_LENGTH` | `1000000` | Maximum document content length |

## Architecture decisions

- PostgreSQL is both the source of truth and the initial search implementation. A generated,
  weighted `tsvector` and GIN index provide document search without a second datastore.
- CRUD repositories, `ClientSearchPort`, `DocumentSearchPort`, and `QueryExpansionPort` are separate
  capabilities. PostgreSQL SQL, `tsquery`, ranking, and mapping-table details stay in infrastructure
  adapters.
- Business expansion is explicit and deterministic. PostgreSQL FTS does not pretend that
  `address proof` and `utility bill` are linguistic synonyms.
- The small `search_term_mapping` table is Flyway-seeded and avoids hard-coding business vocabulary
  in application services.
- Client-specific document operations carry an explicit `clientId` through the call graph and SQL.
  There is no ambient client context.
- The global `/search` facade exists because the task requires it; the primary client-centric path
  remains selecting a client and searching that client's document collection.
- A shared database is appropriate for this implementation. Tenant, RLS, and database-per-client
  models are not introduced. Stronger isolation is a future product and operations trade-off.
- A dedicated engine such as Lucene or OpenSearch becomes justified when search scale, independent
  indexing, filters, analyzers, or operational requirements exceed PostgreSQL FTS. It would replace
  the document-search adapter rather than the application use cases.

## Non-goals

Authentication, authorization, Tenant concepts, RLS, binary upload, PDF extraction, OCR, S3,
asynchronous ingestion, vector search, embeddings, LLM search, and optional summarization are not
part of this implementation.
