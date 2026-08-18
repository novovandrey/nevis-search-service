# Nevis Search Service — Architecture and Implementation Plan

> Status: agreed implementation plan for the Nevis backend home task.
>
> This document is intended to be the primary architecture input for a coding agent. It describes what to build now, the boundaries that must be preserved, the implementation order, and the trade-offs that are intentionally left for future discussion.

## 1. Goal

Build a standalone Java backend service for Nevis that supports client creation, document creation, client discovery, and intelligent document search.

The implementation must satisfy the home-task contract while keeping the code small, testable, and easy to evolve.

Core technology decisions:

- Java 25.
- Spring Boot.
- PostgreSQL as the source of truth.
- PostgreSQL Full Text Search as the initial document-search implementation.
- Flyway for database migrations.
- Docker Compose for local reproducibility.
- Testcontainers PostgreSQL for PostgreSQL-specific integration tests.
- Lightweight Hexagonal Architecture / Ports and Adapters.

The central architecture rule is:

> PostgreSQL is the current implementation choice, not the application architecture.

Application code should depend on capabilities such as searching documents, searching clients, storing documents, and expanding business search terms. PostgreSQL-specific SQL and FTS concepts must remain inside infrastructure adapters.

---

## 2. Scope

### 2.1 Required functionality

Implement the home-task requirements:

1. Create a client.
2. Create a document for a client.
3. Global search through the required `GET /search?q=...` endpoint.
4. Find clients by company-like queries derived from corporate email domains.
   - Example: `Nevis Wealth` should match `anton.batiaev@neviswealth.com`.
5. Find documents by their content.
6. Support business-level related-term expansion.
   - Example: `address proof` should be able to find a document containing `utility bill`.
7. Docker Compose with the backend exposed on port `8080`.
8. Tests for core logic and edge cases.
9. README with setup and search examples.
10. API documentation using OpenAPI/Swagger or Markdown.

### 2.2 Additional endpoint for the expected client-centric workflow

Add:

```http
GET /clients/{clientId}/documents?q={query}
```

This is the expected primary document-search workflow:

```text
find/select client
        |
        v
open client
        |
        v
search that client's documents
```

This endpoint is intentionally modelled as filtering/searching the client's document collection rather than creating another `/search` resource.

Suggested behaviour:

- `q` present: search documents belonging only to `clientId`.
- `q` absent: list documents belonging only to `clientId`.

The exact pagination parameters may be added without changing the architecture.

### 2.3 Explicit non-goals

Do not add these to the initial implementation:

- Tenant model.
- Authentication/authorization system.
- PostgreSQL Row-Level Security.
- Database-per-client topology.
- Elasticsearch/OpenSearch.
- Embedded Lucene.
- Vector database.
- Embeddings.
- LLM-based search.
- Kafka or event-driven indexing.
- Microservice decomposition.
- S3 document storage.
- PDF upload/parsing/OCR pipeline.
- Asynchronous ingestion pipeline.

The task already supplies document `content` as text. Binary document storage and extraction are production concerns outside this implementation.

---

## 3. Domain and data ownership

The initial domain is intentionally small:

```text
Client
  |
  +-- Document
  +-- Document
  +-- Document
```

There is no Tenant entity in this take-home implementation.

### 3.1 Client

Conceptual fields:

```text
Client
------
id
firstName
lastName
email
countryOfResidence
```

### 3.2 Document

Conceptual fields:

```text
Document
--------
id
clientId
title
content
createdAt
```

A document always belongs to exactly one client.

### 3.3 Important client-scope rule

`clientId` is an explicit data scope for client-specific document operations.

For example:

```http
GET /clients/{clientId}/documents?q=address%20proof
```

must result in an infrastructure query that contains an explicit client constraint equivalent to:

```sql
WHERE client_id = :clientId
```

Do not implement a hidden ThreadLocal-style `ClientContext`.

The client scope should be visible in method parameters and application types.

Also note that `clientId` is not an authentication boundary in this take-home. The required global `/search` endpoint intentionally searches across clients. Authentication and advisor/organisation ownership are outside the supplied contract.

---

## 4. Data-isolation strategy

### 4.1 Current implementation

Use one shared PostgreSQL database:

```text
PostgreSQL
  |
  +-- clients
  +-- documents
  +-- search_term_mapping
```

Client-specific document access is protected by explicit `client_id` scoping and foreign-key integrity.

### 4.2 Database-per-client is not implemented now

A stronger future isolation strategy could be:

```text
Client A -> Database A
Client B -> Database B
Client C -> Database C
```

This gives stronger physical isolation because a query against one client database cannot accidentally return another client's rows.

However, it also introduces substantial complexity:

- database provisioning;
- migrations across many databases;
- connection-pool management;
- backups and restore lifecycle;
- monitoring;
- schema-version coordination;
- global-search fan-out or the need for a separate central search index.

Therefore the take-home implementation remains a shared database.

The application ports must not unnecessarily assume that all clients must forever share one physical database. Database-per-client should be treated as an interview/future-design trade-off, not as a planned migration that is required by this solution.

---

## 5. Architectural style

Use a lightweight Hexagonal Architecture / Ports and Adapters design.

```text
                         HTTP
                          |
                          v
                  +---------------+
                  | API           |
                  | Controllers   |
                  | DTOs          |
                  +---------------+
                          |
                          v
                  +---------------+
                  | Application   |
                  | Services      |
                  | Normalization |
                  | Expansion     |
                  +---------------+
                          |
                          v
                  +----------------------+
                  | Ports                |
                  | ClientRepository     |
                  | DocumentRepository   |
                  | ClientSearchPort     |
                  | DocumentSearchPort   |
                  | QueryExpansionPort   |
                  +----------------------+
                          |
                          v
                  +------------------------+
                  | Infrastructure         |
                  | PostgreSQL adapters    |
                  | SQL / FTS / mappings   |
                  +------------------------+
                          |
                          v
                      PostgreSQL
```

This is deliberately lightweight. Do not create abstractions solely to claim that the project is hexagonal.

### 5.1 Dependency rule

The dependency direction is:

```text
API -> Application -> Ports
                       ^
                       |
               Infrastructure
```

Application code must not import PostgreSQL-specific classes or model `tsvector`, `tsquery`, GIN, SQL syntax, or database-specific ranking concepts.

### 5.2 Capability-oriented abstractions

Good:

```java
interface DocumentSearchPort {
    List<DocumentSearchResult> search(
        SearchQuery query,
        DocumentSearchScope scope,
        SearchLimit limit
    );
}
```

Avoid technology-oriented abstractions such as:

```java
GenericDatabaseSearchEngine
GenericFullTextDatabase
```

The port names should express what the application needs, not how PostgreSQL happens to implement it today.

---

## 6. Suggested package structure

```text
com.nevis.search
|
+-- api
|   +-- ClientController
|   +-- DocumentController
|   +-- SearchController
|   +-- ApiExceptionHandler
|   +-- dto
|
+-- application
|   +-- ClientService
|   +-- DocumentService
|   +-- SearchService
|   +-- QueryNormalizer
|   +-- QueryExpander
|   +-- port
|       +-- ClientRepository
|       +-- DocumentRepository
|       +-- ClientSearchPort
|       +-- DocumentSearchPort
|       +-- QueryExpansionPort
|
+-- domain
|   +-- Client
|   +-- Document
|   +-- SearchQuery
|   +-- DocumentSearchScope
|   +-- ClientSearchResult
|   +-- DocumentSearchResult
|
+-- infrastructure
    +-- postgres
        +-- PostgresClientRepository
        +-- PostgresDocumentRepository
        +-- PostgresClientSearchAdapter
        +-- PostgresDocumentSearchAdapter
        +-- PostgresQueryExpansionAdapter
```

Package names may be adjusted if a simpler equivalent is clearer, but the architectural boundaries must remain.

---

## 7. Storage and search are separate capabilities

Even though PostgreSQL initially provides both persistence and search, CRUD and search must remain separate application capabilities.

Example repository port:

```java
interface DocumentRepository {
    Document save(Document document);

    Optional<Document> findById(UUID id);

    List<Document> findByClientId(UUID clientId, int limit, int offset);
}
```

Search is separate:

```java
interface DocumentSearchPort {
    List<DocumentSearchResult> search(
        SearchQuery query,
        DocumentSearchScope scope,
        int limit
    );
}
```

Initial topology:

```text
DocumentRepository  ----------> PostgreSQL relational storage
DocumentSearchPort  ----------> PostgreSQL Full Text Search
```

Possible future topology:

```text
DocumentRepository  ----------> PostgreSQL / another source of truth
DocumentSearchPort  ----------> Lucene / OpenSearch / another search engine
```

Changing the search implementation should not require rewriting controllers or application use cases.

---

## 8. Explicit document-search scope

Document search supports two scopes because the take-home and the expected product workflow differ.

Conceptually:

```java
public sealed interface DocumentSearchScope {

    record AllClients() implements DocumentSearchScope {}

    record Client(UUID clientId) implements DocumentSearchScope {}
}
```

Equivalent implementation types are acceptable.

Usage:

```text
GET /search?q=...
        |
        +--> DocumentSearchScope.AllClients
```

and:

```text
GET /clients/{clientId}/documents?q=...
        |
        +--> DocumentSearchScope.Client(clientId)
```

Do not use `null` to represent the search scope if a small explicit type makes the intent clearer.

This is the replacement for the previously discussed Tenant/Client context idea. The scope is explicit data passed through the call graph rather than ambient hidden state.

---

## 9. PostgreSQL schema direction

### 9.1 `clients`

Conceptual schema:

```sql
CREATE TABLE clients (
    id UUID PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    country_of_residence VARCHAR(100)
);
```

Do not invent a uniqueness rule for email unless it is explicitly documented as an assumption. The task does not state that email must be globally unique.

### 9.2 `documents`

Conceptual schema:

```sql
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    search_vector TSVECTOR
);
```

The actual migration should make `search_vector` automatically derived from searchable fields, either using a stored generated column or another simple PostgreSQL-native mechanism.

Recommended weighting:

```text
title   -> weight A
content -> weight B
```

Create a GIN index on the search vector.

Also create a normal index on:

```text
documents(client_id)
```

because client-scoped document operations are a primary path.

### 9.3 `search_term_mapping`

Use one small table for explicit business-related search terms.

Recommended shape:

```text
search_term_mapping
-------------------
group_key
term
normalized_term
```

Example rows:

```text
proof_of_address | address proof       | address proof
proof_of_address | proof of address    | proof of address
proof_of_address | proof of residency  | proof of residency
proof_of_address | utility bill        | utility bill
proof_of_address | bank statement      | bank statement
```

Recommended key:

```text
PRIMARY KEY (group_key, normalized_term)
```

This is intentionally a small business vocabulary table, not a generic thesaurus system.

It avoids pairwise synonym duplication. Adding a new term to a group adds one row rather than requiring mappings against every other term.

Seed the initial data using Flyway.

---

## 10. Query normalization

Query normalization belongs to the application layer and is search-engine independent.

Responsibilities:

- trim leading/trailing whitespace;
- collapse repeated whitespace;
- lowercase for business-term lookup where appropriate;
- normalise basic punctuation/separators needed for matching;
- validate that the query is not blank;
- enforce a reasonable maximum query length.

Example:

```text
"  Address-Proof  "
        |
        v
"address proof"
```

Do not place synonym/business mappings inside `QueryNormalizer`.

Normalization answers:

> What is the canonical form of this user input?

Expansion answers:

> What additional business-related terms should this query search for?

They are separate responsibilities.

---

## 11. Business-term expansion

The task example:

```text
address proof -> utility bill
```

is not reliably solved by stemming, fuzzy matching, or ordinary linguistic synonyms.

It represents explicit domain knowledge.

Use the following pipeline:

```text
raw query
    |
    v
QueryNormalizer
    |
    v
QueryExpander
    |
    v
original query + expanded alternatives
    |
    v
DocumentSearchPort
```

Example:

```text
address proof
    |
    v
[
  "address proof",
  "proof of address",
  "proof of residency",
  "utility bill",
  "bank statement"
]
```

### 11.1 Port boundary

The application should not depend directly on the PostgreSQL table:

```java
interface QueryExpansionPort {
    Set<String> expand(String normalizedQuery);
}
```

Initial adapter:

```text
PostgresQueryExpansionAdapter
        |
        v
search_term_mapping
```

Future implementations could use:

- configuration/YAML;
- external taxonomy;
- another database;
- a semantic/LLM service;
- concept classification.

The application orchestration does not need to change.

### 11.2 Initial expansion semantics

Keep the first implementation deliberately deterministic:

1. Always include the original normalized query.
2. If the normalized query matches a term in a mapping group, retrieve all terms in that group.
3. Deduplicate terms.
4. Do not recursively expand forever.
5. Do not perform broad fuzzy synonym inference.

This is easy to explain, test, and maintain.

---

## 12. PostgreSQL Full Text Search

PostgreSQL is both:

1. the source of truth for document text/metadata;
2. the initial full-text search implementation.

Use PostgreSQL-native capabilities such as:

- `tsvector`;
- `websearch_to_tsquery` and/or equivalent safe `tsquery` construction;
- GIN index;
- `ts_rank_cd` or equivalent ranking.

Conceptually:

```sql
setweight(to_tsvector('english', coalesce(title, '')), 'A')
||
setweight(to_tsvector('english', coalesce(content, '')), 'B')
```

For expanded search terms, the PostgreSQL adapter should interpret the alternatives using OR semantics.

Conceptually:

```text
"address proof"
OR
"proof of address"
OR
"utility bill"
OR
"bank statement"
```

The adapter is responsible for translating the application search request into a safe PostgreSQL query.

The application layer should never build PostgreSQL `tsquery` syntax itself.

### 12.1 Client-scoped FTS

For a client-specific request, the SQL must apply both conditions:

```text
client_id = requested client
AND
search_vector matches query
```

The client predicate is mandatory even if the FTS index is used.

### 12.2 Global FTS

The required global `/search` endpoint intentionally uses an all-clients document scope.

This exists to satisfy the supplied take-home contract.

It should reuse the same `DocumentSearchPort`; do not create a duplicate global-search SQL implementation.

---

## 13. Client/company search

Client search is a separate capability from document full-text search.

Required example:

```text
query: Nevis Wealth
email: anton.batiaev@neviswealth.com
```

A simple deterministic strategy is sufficient for the take-home.

### 13.1 Normalization

For company/domain matching:

```text
Nevis Wealth
     |
     v
neviswealth
```

For the email domain:

```text
anton.batiaev@neviswealth.com
                  |
                  v
neviswealth.com
                  |
                  v
neviswealthcom
```

The normalized query can then match the normalized email domain using a prefix/contains strategy.

This avoids introducing public-suffix parsing or external company-resolution services for a single task requirement.

Client search may also consider:

- first name;
- last name;
- full email;
- email domain.

### 13.2 Ranking

Keep ranking deterministic and easy to explain, for example:

```text
exact/full email match
        >
domain/company-normalized match
        >
name prefix match
        >
name/email contains match
```

Do not try to compare this score mathematically with PostgreSQL document `ts_rank` values. They represent different relevance models.

### 13.3 Port

```java
interface ClientSearchPort {
    List<ClientSearchResult> search(SearchQuery query, int limit);
}
```

Initial implementation:

```text
PostgresClientSearchAdapter
```

Client search does not need PostgreSQL FTS unless it materially simplifies the implementation. Simple normalized relational matching is sufficient.

---

## 14. API design

### 14.1 `POST /clients`

Request follows the supplied contract.

Expected result:

```text
201 Created
```

Validation:

- first name must not be blank;
- last name must not be blank;
- email must be syntactically valid;
- field lengths must be bounded.

### 14.2 `POST /clients/{id}/documents`

Creates a document belonging to the client.

Expected behaviour:

- `201 Created` on success;
- `404 Not Found` when the client does not exist;
- `400 Bad Request` for invalid content/title.

The take-home deals with text content, not binary files.

Use a documented configurable maximum request/content size rather than accepting unbounded document text.

### 14.3 `GET /clients/{clientId}/documents`

Extension supporting the expected product workflow.

Examples:

```http
GET /clients/{clientId}/documents
```

lists that client's documents.

```http
GET /clients/{clientId}/documents?q=address%20proof
```

searches only that client's documents.

Expected behaviour:

- `200 OK` with a list;
- `404 Not Found` if the client itself does not exist;
- never return another client's documents in this endpoint.

### 14.4 Required global `GET /search`

Keep the endpoint supplied by the assignment:

```http
GET /search?q={query}
```

It is a small application-level facade over two separate search capabilities:

```text
                     SearchService
                    /             \
                   /               \
          ClientSearchPort    DocumentSearchPort
                                  |
                             AllClients scope
```

Do not implement one giant repository query that mixes client and document search logic.

### 14.5 Global search response

Stay close to the supplied OpenAPI, which defines the response as an array of objects.

Use one polymorphic response type with a discriminator:

```json
[
  {
    "type": "CLIENT",
    "id": "...",
    "firstName": "Anton",
    "lastName": "Batiaev",
    "email": "anton.batiaev@neviswealth.com"
  },
  {
    "type": "DOCUMENT",
    "id": "...",
    "clientId": "...",
    "title": "Utility Bill"
  }
]
```

The Java API model may use a sealed interface or another clear polymorphic representation.

Do not invent a fake shared relevance score across clients and documents.

Ordering can remain deterministic and documented, for example:

1. client results ordered by client-search relevance;
2. document results ordered by PostgreSQL FTS relevance.

The global endpoint is primarily required by the task. The expected client-centric product workflow remains `find/select client -> search that client's documents`.

### 14.6 Future API split

If product requirements later need dedicated filtering, pagination, or independent result pages, the architecture already supports endpoints such as:

```text
GET /clients?q=...
GET /clients/{clientId}/documents?q=...
GET /documents/search?...   (only if a true global document-search use case emerges)
```

The current `/search` endpoint can remain as a global-search facade rather than being removed.

---

## 15. Application flows

### 15.1 Create client

```text
HTTP POST /clients
        |
        v
ClientController
        |
        v
ClientService
        |
        v
ClientRepository
        |
        v
PostgresClientRepository
        |
        v
PostgreSQL
```

### 15.2 Create document

```text
HTTP POST /clients/{id}/documents
        |
        v
DocumentController
        |
        v
DocumentService
        |
        +--> verify client exists
        |
        v
DocumentRepository
        |
        v
PostgreSQL
```

### 15.3 Client-scoped document search

```text
GET /clients/{clientId}/documents?q=address+proof
        |
        v
DocumentController
        |
        v
DocumentService / search use case
        |
        +--> validate client exists
        |
        +--> QueryNormalizer
        |
        +--> QueryExpander
        |
        v
DocumentSearchPort
scope = Client(clientId)
        |
        v
PostgresDocumentSearchAdapter
        |
        v
PostgreSQL FTS
WHERE client_id = :clientId
```

### 15.4 Required global search

```text
GET /search?q=address+proof
        |
        v
SearchController
        |
        v
SearchService
        |
        +--> QueryNormalizer
        |
        +--> ClientSearchPort
        |
        +--> QueryExpander
        |
        +--> DocumentSearchPort
             scope = AllClients
        |
        v
combine typed results
        |
        v
SearchResult[]
```

The `SearchService` orchestrates capabilities. It should not contain PostgreSQL SQL or FTS syntax.

---

## 16. Validation and error handling

Use a consistent API error model.

At minimum handle:

- malformed JSON -> `400`;
- invalid email -> `400`;
- blank required names/title/content -> `400`;
- blank search query -> `400`;
- excessively long query -> `400`;
- unknown client in document create/list/search -> `404`;
- unexpected internal failure -> `500` without leaking SQL/database details.

Use bean validation for request DTOs where appropriate and one centralized exception handler.

Do not leak document content, SQL statements containing sensitive values, or stack traces in API responses.

---

## 17. Testing strategy

PostgreSQL-specific behaviour must be tested against PostgreSQL, not H2.

Use Testcontainers for integration tests involving:

- migrations;
- FTS;
- GIN-backed queries;
- generated/search-vector behaviour;
- PostgreSQL-specific normalization SQL;
- synonym table lookup.

### 17.1 Unit tests

Cover pure application behaviour:

- query normalization;
- business-term expansion orchestration;
- deduplication of expansion terms;
- no mapping -> original query only;
- global SearchService orchestration;
- scope selection;
- result mapping.

### 17.2 Client-search integration tests

At minimum:

```text
Nevis Wealth -> anton.batiaev@neviswealth.com
```

Also test:

- case differences;
- spaces/punctuation;
- exact email search;
- name search;
- no result.

### 17.3 Document-search integration tests

At minimum:

- exact term in title;
- exact term in content;
- title weighting/ranking;
- stemming expected from the selected PostgreSQL text configuration;
- no result;
- multiple results ordered by relevance.

### 17.4 Business-mapping tests

At minimum:

```text
address proof      -> utility bill document is found
proof of address   -> same group
proof of residency -> same group
utility bill       -> same group
bank statement     -> same group
```

Also include a negative case demonstrating that unrelated terms are not expanded.

### 17.5 Client-scope isolation tests

These replace the previously considered tenant/RLS tests.

Prepare:

```text
Client A -> Document A
Client B -> Document B
```

Verify:

1. `GET /clients/A/documents` returns A and never B.
2. `GET /clients/A/documents?q=...` never returns B even when B has a stronger FTS match.
3. `GET /clients/B/documents?q=...` never returns A.
4. Creating a document for an unknown client fails.
5. The foreign key prevents a document from pointing to a non-existing client.

The required global `/search` test should separately demonstrate that all-client scope is intentional for that endpoint.

### 17.6 API tests

Cover:

- `201` for client creation;
- `201` for document creation;
- `404` unknown client;
- `400` invalid input;
- global search response discriminator;
- client-scoped document search;
- empty/no-result responses.

---

## 18. PostgreSQL-specific implementation boundary

PostgreSQL-specific code is allowed in:

- infrastructure adapters;
- SQL;
- Flyway migrations;
- index definitions;
- `tsvector`/`tsquery` handling;
- PostgreSQL integration tests.

It must not leak into:

- API DTOs;
- controllers;
- `SearchService`;
- `ClientService`;
- `DocumentService`;
- `SearchQuery`;
- `DocumentSearchScope`;
- `QueryNormalizer`;
- domain entities;
- port interfaces.

This boundary is the main mechanism that keeps future search-engine replacement localised.

---

## 19. Optional document summary

The task marks quick document summary as optional.

Do not make it part of the critical implementation path.

Preferred default for the take-home:

```text
finish CRUD + search + mappings + tests + documentation first
```

If there is significant time left, summarization may be added behind a separate capability such as:

```java
interface DocumentSummaryPort {
    DocumentSummary summarize(Document document);
}
```

Do not couple the main search implementation to an LLM provider merely to implement the optional feature.

---

## 20. Runtime and reproducibility

Use Docker Compose with at least:

```text
app
postgres
```

Requirements:

- application exposed on host port `8080`;
- PostgreSQL health check;
- application starts only when the database is available or handles startup retries correctly;
- Flyway migrations execute automatically during application startup;
- no manual database setup required after `docker compose up`;
- configuration through environment variables with sensible local defaults;
- PostgreSQL image version pinned rather than using `latest`.

The README must include the exact command required to run the system.

---

## 21. API documentation

Document at least:

```text
POST /clients
POST /clients/{id}/documents
GET  /clients/{clientId}/documents
GET  /search
```

Include:

- request shapes;
- response shapes;
- validation errors;
- example global client search;
- example related-term document search;
- example client-scoped document search.

If Swagger/OpenAPI is generated from the application, ensure the generated contract remains close to the supplied task contract.

---

## 22. README content

The README should be short but decision-oriented.

Include:

### Setup

- prerequisites;
- Docker Compose command;
- local URL;
- how to run tests.

### Example workflow

1. create Client A;
2. create a utility-bill document for Client A;
3. search `address proof` within Client A;
4. demonstrate that the utility bill is returned;
5. use global `/search` with `Nevis Wealth` and show a client match.

### Architecture decisions

Explain briefly:

- why PostgreSQL is both source of truth and initial search implementation;
- why PostgreSQL-specific search is behind ports/adapters;
- why business-term expansion is explicit instead of pretending that FTS alone provides semantic search;
- why `search_term_mapping` is a table rather than hard-coded business logic;
- why client-scoped document search is explicit;
- why the global `/search` endpoint exists;
- why Tenant/RLS/database-per-client are not implemented;
- when a different search engine might become justified.

### Non-goals

Explicitly state that file upload/OCR/S3/vector search are outside the assignment's current input model.

---

## 23. Observability and logging

Keep observability proportional to the home task.

At minimum:

- log request failures with useful context;
- log search latency at debug/info level if easy to add;
- do not log full document content;
- do not log unnecessary personally identifiable client data;
- avoid logging full search SQL with embedded sensitive values.

Do not build a monitoring stack for the take-home.

---

## 24. Future evolution

### 24.1 Replace PostgreSQL FTS

Current:

```text
DocumentSearchPort
        |
        v
PostgresDocumentSearchAdapter
```

Future:

```text
DocumentSearchPort
        |
        +--> LuceneDocumentSearchAdapter
```

or:

```text
DocumentSearchPort
        |
        +--> OpenSearchDocumentSearchAdapter
```

The following should remain largely unchanged:

- controllers;
- application services;
- query normalization;
- business-term expansion;
- document search scope;
- API result models.

### 24.2 Move business mappings

Current:

```text
QueryExpansionPort
        |
        v
PostgresQueryExpansionAdapter
        |
        v
search_term_mapping
```

Future implementation can move to another store or service without changing `QueryExpander` callers.

### 24.3 Split search APIs when product requirements demand it

Potential reasons:

- client directory has its own filters/pagination;
- document search has document-type/date filters;
- client and document result sets require independent pagination;
- client page requires only client-specific documents;
- global search adds additional entity types such as meeting notes.

The global `/search` facade can continue to exist while specialised APIs evolve independently.

### 24.4 Stronger client isolation

Database-per-client is a possible future strategy, not an implementation requirement.

If this becomes necessary, evaluate together:

- number of clients;
- regulatory/security requirements;
- database provisioning model;
- migration orchestration;
- connection management;
- global-search architecture;
- backup/restore requirements;
- operational cost.

Do not claim that database-per-client is always superior. It is a security/operations trade-off that depends on scale and product requirements.

### 24.5 Production binary document storage

If the product later accepts actual PDFs or large binary files, a likely production direction is:

```text
raw binary file -> object storage such as S3
metadata/extracted searchable text -> database/search system
```

This should be a separate ingestion/storage decision and must not complicate the current text-based home-task API.

---

## 25. Implementation sequence for the coding agent

The coding agent should implement the project incrementally in this order.

### Phase 0 — Repository bootstrap

1. Java 25 project.
2. Spring Boot compatible with Java 25.
3. Maven build unless the repository already establishes another build tool.
4. PostgreSQL driver.
5. Flyway.
6. Validation.
7. Testcontainers PostgreSQL.
8. Dockerfile.
9. Docker Compose.
10. Basic application startup test.

Do not implement search before the project and PostgreSQL test environment are running reliably.

### Phase 1 — Schema and domain

1. Create Flyway migration for `clients`.
2. Create Flyway migration for `documents`.
3. Add FK from `documents.client_id` to `clients.id`.
4. Add document search vector.
5. Add GIN search index.
6. Add `documents(client_id)` index.
7. Create `search_term_mapping`.
8. Seed initial `proof_of_address` mappings.
9. Add domain/application models and ports.

Run migrations through Testcontainers before continuing.

### Phase 2 — Client CRUD

1. Implement `POST /clients`.
2. Add validation.
3. Add repository adapter.
4. Add API/integration tests.

### Phase 3 — Document CRUD and client collection

1. Implement `POST /clients/{id}/documents`.
2. Reject unknown client with `404`.
3. Implement `GET /clients/{clientId}/documents` without `q`.
4. Add strict `clientId` scoping tests.

### Phase 4 — Client search

1. Implement query normalization needed for company/email-domain matching.
2. Implement `ClientSearchPort` PostgreSQL adapter.
3. Verify `Nevis Wealth -> @neviswealth.com`.
4. Add deterministic ordering.
5. Add integration tests.

Do not connect this to `/search` yet if isolated tests are not passing.

### Phase 5 — Business-term expansion

1. Implement `QueryNormalizer`.
2. Implement `QueryExpansionPort`.
3. Implement PostgreSQL mapping adapter.
4. Implement `QueryExpander` orchestration.
5. Test `address proof -> utility bill` and the entire mapping group.

### Phase 6 — PostgreSQL document FTS

1. Implement `DocumentSearchPort`.
2. Support `DocumentSearchScope.Client`.
3. Support `DocumentSearchScope.AllClients`.
4. Apply title/content weighting.
5. Apply FTS ranking.
6. Integrate expanded OR terms safely.
7. Add PostgreSQL integration tests.
8. Verify a stronger match belonging to Client B cannot appear in Client A scoped search.

### Phase 7 — Client-scoped document search

Connect:

```http
GET /clients/{clientId}/documents?q=...
```

Pipeline:

```text
normalize -> expand -> search(scope=Client(clientId))
```

Add API tests.

### Phase 8 — Required global search facade

Implement:

```http
GET /search?q=...
```

`SearchService` should:

1. normalize/validate query;
2. search clients;
3. expand query for document search;
4. search documents with `AllClients` scope;
5. map to one typed `SearchResult[]` response;
6. preserve deterministic per-type ordering.

Do not merge client and document relevance into a fake common score.

### Phase 9 — Error handling and hardening

1. Central exception handler.
2. Query length limits.
3. Document content/request size limit.
4. Consistent error schema.
5. Review logs for sensitive data.
6. Review SQL for explicit client scoping in client-specific paths.

### Phase 10 — Documentation and final verification

1. OpenAPI/Swagger.
2. README setup.
3. README example requests/responses.
4. README architecture/trade-offs.
5. `docker compose up --build` smoke test.
6. Full automated test suite.
7. Verify port `8080`.
8. Verify no Tenant/RLS/database-per-client implementation accidentally remains.
9. Verify PostgreSQL-specific code remains inside infrastructure.

Only after these phases are complete should the optional summarization feature be considered.

---

## 26. Acceptance criteria

The implementation is complete when all of the following are true.

### Functional

- Client can be created.
- Document can be created for an existing client.
- Unknown client document creation returns `404`.
- `Nevis Wealth` can find a client with `@neviswealth.com`.
- `address proof` can find a document containing `utility bill` through explicit business-term expansion.
- Client-scoped search returns only documents belonging to that client.
- Required global `/search` returns typed client/document results.

### Architecture

- PostgreSQL FTS is behind `DocumentSearchPort`.
- Client search is behind `ClientSearchPort`.
- Business mapping storage is behind `QueryExpansionPort`.
- CRUD repositories are separate from search ports.
- No PostgreSQL-specific FTS types leak into application/domain APIs.
- No Tenant model exists.
- No hidden ClientContext exists.
- Client-specific search scope is explicit.
- Database-per-client is not implemented.

### Quality

- Flyway owns schema changes.
- PostgreSQL-specific integration tests run through Testcontainers.
- Core logic and edge cases are covered.
- Docker Compose runs the full service on port `8080`.
- README contains setup and examples.
- API is documented.

---

## 27. Decisions summary

| Area | Decision |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot |
| Architectural style | Lightweight Hexagonal / Ports & Adapters |
| Source of truth | Shared PostgreSQL database |
| Initial document search | PostgreSQL Full Text Search |
| Search index | `tsvector` + GIN |
| PostgreSQL coupling | Infrastructure adapters only |
| CRUD vs search | Separate capabilities/ports |
| Client search | Dedicated `ClientSearchPort` |
| Document search | Dedicated `DocumentSearchPort` |
| Query normalization | Application-level component |
| Related-term mechanism | Explicit business mapping |
| Mapping storage | Small PostgreSQL `search_term_mapping` table |
| Mapping abstraction | `QueryExpansionPort` |
| Tenant | Not modelled |
| Client-specific document scope | Explicit `clientId` / `DocumentSearchScope.Client` |
| Hidden ClientContext | Not used |
| Global document scope | Used only by required `/search` facade |
| Primary expected UX | Select client, then search within that client's documents |
| Global `/search` | Implemented to satisfy task contract; kept as facade |
| `/search` response | One typed/polymorphic result array |
| Cross-type global score | Not introduced |
| Client document endpoint | `GET /clients/{clientId}/documents?q=...` |
| Database-per-client | Future/interview trade-off only |
| RLS | Not implemented |
| Synonym/semantic engine | No generic engine; deterministic mappings only |
| Elasticsearch/OpenSearch | Not used initially |
| Lucene | Possible future adapter |
| Embeddings/vector DB | Not required |
| Binary/S3 storage | Production future concern |
| Optional summary | Only after core solution is complete |
| Schema migration | Flyway |
| PostgreSQL integration tests | Testcontainers |
| Runtime | Docker Compose, app on `8080` |

---

## 28. Architecture principles to preserve during implementation

1. **Keep the take-home small.** Do not build infrastructure that the requirements do not justify.
2. **Keep client scope explicit.** Client-specific document operations must carry the client identifier through the call graph.
3. **Do not confuse data scope with authentication.** Authentication/tenant ownership is outside the supplied contract.
4. **Separate business semantics from search technology.** `address proof -> utility bill` belongs to query expansion, not PostgreSQL FTS logic.
5. **Separate storage from search.** They happen to share PostgreSQL now but remain different capabilities.
6. **Localise PostgreSQL-specific code.** A future Lucene/OpenSearch adapter should primarily replace infrastructure, not application logic.
7. **Prefer deterministic behaviour.** The take-home should be explainable without hidden LLM behaviour.
8. **Test the database feature actually used.** PostgreSQL FTS must be tested against PostgreSQL.
9. **Do not silently broaden the architecture.** If implementation requires changing these decisions, document the discrepancy before changing the design.
10. **Preserve interview-worthy trade-offs.** Shared DB vs database-per-client, PostgreSQL FTS vs dedicated search, and global vs client-scoped search are deliberate decisions that should be explainable rather than hidden.
