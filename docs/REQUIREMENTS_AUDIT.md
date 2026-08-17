# Nevis Home Task — Requirements Audit

## Purpose

Review the **existing implementation** against the original Nevis backend home task.

This is an audit of an already implemented solution.

Do **not** redesign the system or reimplement features that already satisfy the assignment.

The goals are:

1. verify every mandatory requirement from the original task;
2. find missing or incorrectly implemented behavior;
3. verify that appropriate automated tests exist;
4. make only the smallest necessary fixes for failed requirements;
5. produce a final compliance report with evidence.

The existing architecture and implementation decisions should be preserved unless they directly prevent a mandatory requirement from being satisfied.

---

## Source of Truth

Use the original Nevis home task as the source of truth for mandatory requirements.

The task requires a simplified Search API across clients and documents with these core use cases:

- find clients for their company using corporate email addresses;
- find documents based on similar terms from their content;
- optionally provide a quick document summary.

The provided API contract includes:

```http
POST /clients
POST /clients/{id}/documents
GET /search?q=...
```

The repository must also provide:

- source code;
- Docker Compose;
- service exposed on port `8080`;
- tests for core logic and edge cases;
- `README.md` with setup instructions;
- `README.md` with example search queries and responses;
- API documentation using Swagger/OpenAPI or Markdown;
- appropriate REST conventions and HTTP status codes.

The optional document-summary feature must **not** be treated as a failed requirement if it is not implemented.

---

# Audit Rules

## 1. Inspect before changing

For every requirement:

1. locate the relevant production code;
2. locate the relevant automated tests;
3. inspect the actual behavior;
4. run the appropriate tests;
5. mark the requirement as `PASS` or `FAIL`;
6. if it fails, identify the smallest corrective change;
7. implement the fix;
8. add or update tests proving the fix.

Do not make speculative architecture changes during this audit.

---

## 2. Preserve existing architecture

The current implementation intentionally uses:

- Java 25;
- Spring Boot;
- PostgreSQL as the source of truth;
- PostgreSQL Full Text Search as the current document-search implementation;
- application ports/interfaces so that PostgreSQL-specific search behavior can be replaced later;
- a shared PostgreSQL database;
- explicit `clientId` scoping for client-specific document operations.

Do not replace these choices merely because another design is possible.

Database-per-client, Lucene, OpenSearch, tenant isolation, RLS, embeddings, vector databases, external LLM search, OCR, S3 document storage, Kafka, or other production-scale extensions are **not** part of this audit.

---

## 3. Additional endpoints are allowed

The implementation may contain additional endpoints such as client-scoped document search.

Do not remove additional behavior merely because it was not required by the assignment, provided that:

- it does not break the mandatory contract;
- it does not make the required behavior ambiguous;
- it does not introduce incorrect security or data-scoping behavior.

The mandatory `GET /search?q=...` endpoint must still work.

---

# Mandatory Requirements Audit

## A. Client creation

### Requirement

The API must support:

```http
POST /clients
```

with required fields:

- `first_name`
- `last_name`
- `email`

and optional:

- `countryOfResidence`

Successful creation should return HTTP `201`.

### Verify

- [ ] Endpoint exists.
- [ ] Required request fields are validated.
- [ ] Email validation is appropriate.
- [ ] Client is persisted.
- [ ] Response contains the created client.
- [ ] Response status is `201 Created`.
- [ ] Invalid requests return an appropriate 4xx response.
- [ ] Relevant integration/API tests exist.

### Evidence to record

- controller class and method;
- service/use-case implementation;
- persistence implementation;
- relevant test class and test names.

---

## B. Document creation for a client

### Requirement

The API must support:

```http
POST /clients/{id}/documents
```

with required fields:

- `title`
- `content`

Successful creation should return HTTP `201`.

The resulting document contains at least:

- `id`
- `client_id`
- `title`
- `content`
- `created_at`

### Verify

- [ ] Endpoint exists.
- [ ] `clientId` is taken explicitly from the path.
- [ ] The referenced client must exist.
- [ ] Required fields are validated.
- [ ] Document is associated with the correct client.
- [ ] Response status is `201 Created`.
- [ ] Unknown client returns an appropriate 4xx response, normally `404`.
- [ ] Relevant integration/API tests exist.

### Important edge case

A document created for Client A must never be associated with Client B.

---

## C. Mandatory global search endpoint

### Requirement

The provided contract requires:

```http
GET /search?q=...
```

### Verify

- [ ] Endpoint exists.
- [ ] `q` is required.
- [ ] Blank or invalid query input is handled predictably.
- [ ] Search can return client results.
- [ ] Search can return document results.
- [ ] Response format is documented.
- [ ] HTTP status codes follow REST conventions.
- [ ] Automated tests exercise the endpoint.

Do not replace this endpoint with only specialized client/document search endpoints.

---

# Critical Acceptance Case 1 — Company Search

## Requirement from the task

A query for a company should find clients through their corporate email addresses.

Required example:

```text
Query:
Nevis Wealth

Existing client:
anton.batiaev@neviswealth.com

Expected:
The client is returned.
```

### Verify behavior end-to-end

- [ ] Create or load a client with email `anton.batiaev@neviswealth.com`.
- [ ] Execute:

```http
GET /search?q=Nevis%20Wealth
```

- [ ] Verify that the expected client is present in the result.

### Important

The requirement is specifically about finding a client **for their company using the corporate email**.

Do not consider the requirement satisfied merely because searching for `Anton` or `Batiaev` finds the client.

The implementation must demonstrate the actual mapping:

```text
Nevis Wealth
    ↓
normalized company representation
    ↓
neviswealth.com
    ↓
anton.batiaev@neviswealth.com
```

### Verify implementation quality

- [ ] Company/domain matching is deterministic.
- [ ] Matching is not hard-coded specifically for `Nevis Wealth`.
- [ ] Matching logic is covered by focused tests.
- [ ] PostgreSQL-specific implementation details do not leak unnecessarily into the application layer.

### Minimum tests

At minimum verify:

```text
"Nevis Wealth"  -> match
"nevis wealth"  -> match
"NEVIS WEALTH"  -> match
"Other Company" -> no match
```

Whether searching by first name, last name, or full email is also supported is **not** required to satisfy this particular business requirement.

---

# Critical Acceptance Case 2 — Similar-Term Document Search

## Requirement from the task

Searching for one business concept must be able to find documents that use a related term.

Required example:

```text
Query:
address proof

Document content:
... utility bill ...

Expected:
The document is returned.
```

### Verify behavior end-to-end

- [ ] Create or load a document containing `utility bill`.
- [ ] Execute:

```http
GET /search?q=address%20proof
```

- [ ] Verify that the document is returned.

### Verify architecture

The current intended implementation is:

```text
raw query
   ↓
normalization
   ↓
query expansion / term mapping
   ↓
PostgreSQL Full Text Search
   ↓
document results
```

Verify that:

- [ ] PostgreSQL FTS is actually used for document content search.
- [ ] Related-term expansion is not hard-coded inside controller logic.
- [ ] Related-term storage is isolated behind an abstraction.
- [ ] The current small term-mapping table works.
- [ ] Document ranking/order is deterministic enough for tests.
- [ ] Relevant integration tests use real PostgreSQL behavior where needed.

### Required mapping

At minimum, the implemented data/configuration must allow:

```text
address proof -> utility bill
```

Do not mark this requirement as passing based only on literal FTS matching.

---

# D. Client-scoped document behavior

This is an implementation extension, but its correctness should still be audited.

If the application exposes behavior such as:

```http
GET /clients/{clientId}/documents?q=...
```

verify:

- [ ] search is explicitly scoped by `clientId`;
- [ ] documents belonging to another client are not returned;
- [ ] the SQL/data-access implementation includes the appropriate client predicate;
- [ ] cross-client negative tests exist.

This endpoint is **additional functionality** and does not replace the mandatory global `/search`.

---

# E. PostgreSQL and persistence

### Verify

- [ ] PostgreSQL is the application's persistence database.
- [ ] Database schema is managed through Flyway migrations.
- [ ] Required tables and indexes are created by migrations.
- [ ] PostgreSQL FTS indexes/search vectors are reproducible from migrations or application setup.
- [ ] No manual database setup is required beyond documented commands.
- [ ] Referential integrity between clients and documents is enforced.
- [ ] Relevant database integration tests use PostgreSQL/Testcontainers rather than relying on H2 for PostgreSQL-specific behavior.

---

# F. Similar-term mapping table

The current design uses a small database table for search-term relationships.

### Verify

- [ ] The table is created by a migration.
- [ ] Initial required mappings are reproducible.
- [ ] Query-expansion logic is behind an application abstraction/port.
- [ ] Application business logic is not coupled directly to the table schema.
- [ ] The implementation could later be replaced by another source without redesigning the search orchestration.

Do not replace the current implementation during this audit unless it fails a requirement.

---

# G. HTTP and REST behavior

Review all mandatory endpoints for proper HTTP semantics.

### Verify

- [ ] `POST /clients` -> `201` on success.
- [ ] `POST /clients/{id}/documents` -> `201` on success.
- [ ] `GET /search` -> `200` on success.
- [ ] validation failures return appropriate 4xx responses.
- [ ] missing resources return appropriate 4xx responses.
- [ ] unexpected server failures are not incorrectly reported as `200`.
- [ ] error responses have a consistent shape if the application defines one.

Avoid overengineering the error model solely for this audit.

---

# H. API documentation

The assignment requires API documentation using Swagger or Markdown.

### Verify

- [ ] Mandatory endpoints are documented.
- [ ] Request models are documented.
- [ ] Response models are documented.
- [ ] Search response types are understandable.
- [ ] HTTP status codes are documented.
- [ ] Additional endpoints are clearly distinguishable from the required API.
- [ ] Swagger/OpenAPI can be opened successfully if Swagger is used.

If generated OpenAPI is used, ensure that it reflects the actual implementation rather than stale definitions.

---

# I. Docker Compose and reproducibility

### Requirement

The repository must include Docker Compose and expose the service on port `8080`.

### Verify from a clean environment

Run:

```bash
docker compose up --build
```

Then verify:

- [ ] PostgreSQL starts successfully.
- [ ] the application starts successfully;
- [ ] Flyway migrations complete successfully;
- [ ] the service is reachable on port `8080`;
- [ ] no undocumented local dependencies are required.

Perform at least one API smoke test against the running Docker Compose environment.

---

# J. Tests

The task explicitly requires tests for core logic and edge cases.

### Verify coverage of at least

- [ ] client creation;
- [ ] client validation;
- [ ] document creation;
- [ ] missing client when creating a document;
- [ ] company-name-to-corporate-domain client search;
- [ ] literal document search;
- [ ] similar-term document search;
- [ ] empty/blank search input;
- [ ] client-scoped document isolation if the additional endpoint exists;
- [ ] PostgreSQL-specific FTS behavior;
- [ ] REST status codes for key failure cases.

Do not optimize for an arbitrary line-coverage percentage.

The audit is concerned with behavioral coverage.

---

# K. README

### Verify that `README.md` contains

- [ ] prerequisites;
- [ ] setup instructions;
- [ ] how to run the application;
- [ ] how to run it with Docker Compose;
- [ ] how to run tests;
- [ ] example client creation request/response;
- [ ] example document creation request/response;
- [ ] example global search request/response;
- [ ] example `Nevis Wealth` company search;
- [ ] example `address proof` -> `utility bill` search;
- [ ] location of Swagger/OpenAPI documentation;
- [ ] concise description of important architectural trade-offs.

Do not turn the README into a large architecture document.

Keep detailed architecture discussion in the existing architecture documentation.

---

# L. Optional document summary

The assignment marks quick document summarization as optional.

Audit it only if the feature already exists.

If it does not exist:

```text
Status: NOT IMPLEMENTED — OPTIONAL
```

This must **not** fail the home-task audit.

If it does exist, verify that it does not compromise the mandatory functionality.

---

# M. Architecture boundary check

The audit should verify, but not redesign, the intended replaceability of infrastructure.

Check that application-level search orchestration does not directly depend on PostgreSQL FTS SQL.

Expected conceptual separation:

```text
Application / Search Service
          |
          +--> ClientSearchPort
          |
          +--> DocumentSearchPort
          |
          +--> QueryExpansionPort
```

with PostgreSQL implementations in the infrastructure layer.

### Verify

- [ ] PostgreSQL FTS details are contained in the PostgreSQL adapter/repository layer.
- [ ] query-expansion storage details are contained in their adapter/repository layer.
- [ ] controllers do not contain SQL/search-engine logic.
- [ ] replacing the document-search adapter would not require rewriting controllers or core use cases.

Do not introduce abstractions solely to satisfy this checklist if the existing design already provides a clean boundary.

---

# N. Explicitly out of scope

Do not add these features during the audit:

- tenant model;
- TenantContext;
- PostgreSQL RLS;
- authentication/authorization system not requested by the task;
- database-per-client implementation;
- Elasticsearch/OpenSearch;
- embedded Lucene;
- vector database;
- embeddings;
- semantic LLM search;
- OCR;
- PDF parsing;
- S3 binary storage;
- Kafka;
- asynchronous indexing;
- Kubernetes;
- Redis;
- multi-region deployment.

Some of these may be valid production evolutions, but they are not needed to complete this assignment.

---

# Final End-to-End Verification

Before declaring the audit complete, execute the complete solution as a user would.

## Scenario 1 — create client

Create:

```json
{
  "first_name": "Anton",
  "last_name": "Batiaev",
  "email": "anton.batiaev@neviswealth.com",
  "countryOfResidence": "PT"
}
```

Verify HTTP `201`.

---

## Scenario 2 — create document

For Anton's client ID, create a document such as:

```json
{
  "title": "May Utility Bill",
  "content": "Utility bill for the client's current residential address."
}
```

Verify HTTP `201`.

---

## Scenario 3 — company search

Execute:

```http
GET /search?q=Nevis%20Wealth
```

Verify that Anton is returned.

---

## Scenario 4 — similar-term document search

Execute:

```http
GET /search?q=address%20proof
```

Verify that the utility-bill document is returned.

---

## Scenario 5 — restart/reproducibility

Start the repository through the documented Docker Compose flow and repeat the mandatory search scenarios.

The solution must not rely on manually created database structures or manually inserted required synonym data that is absent from migrations/setup.

---

# Final Compliance Table

At the end of the work, produce a table in the final response or audit notes with this exact structure:

| Requirement | Status | Evidence | Fix made |
|---|---|---|---|
| POST /clients | PASS / FAIL | code + test references | none / description |
| POST /clients/{id}/documents | PASS / FAIL | code + test references | none / description |
| GET /search?q= | PASS / FAIL | code + test references | none / description |
| Company search via corporate email | PASS / FAIL | code + test references | none / description |
| Similar-term document search | PASS / FAIL | code + test references | none / description |
| REST/HTTP codes | PASS / FAIL | code + test references | none / description |
| PostgreSQL persistence | PASS / FAIL | code + migration references | none / description |
| PostgreSQL FTS | PASS / FAIL | code + test references | none / description |
| Tests for core logic and edge cases | PASS / FAIL | test references | none / description |
| Docker Compose | PASS / FAIL | execution evidence | none / description |
| Port 8080 | PASS / FAIL | execution evidence | none / description |
| README setup instructions | PASS / FAIL | README section | none / description |
| README examples | PASS / FAIL | README section | none / description |
| API documentation | PASS / FAIL | Swagger/OpenAPI/Markdown reference | none / description |
| Optional summary | OPTIONAL | implementation reference or N/A | none / description |

---

# Completion Rule

The audit is complete only when:

1. every mandatory row in the final compliance table is `PASS`;
2. the two task-defining acceptance scenarios pass end-to-end:
   - `Nevis Wealth` -> `anton.batiaev@neviswealth.com`;
   - `address proof` -> document containing `utility bill`;
3. the complete automated test suite passes;
4. the Docker Compose deployment starts successfully on port `8080`;
5. the README and API documentation describe the actual implemented behavior.

If a mandatory requirement cannot be made to pass, report it explicitly rather than hiding or reinterpreting the requirement.
