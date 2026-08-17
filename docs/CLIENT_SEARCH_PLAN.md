# Client Company Search — Implementation Plan

## Goal

Implement the client-search requirement from the Nevis home task as an isolated capability.

The required business case is:

- a user searches by company name;
- the system finds clients belonging to that company by using their corporate email addresses;
- example: query `Nevis Wealth` should match a client with email `anton.batiaev@neviswealth.com`.

This task is intentionally separated from document search and from the broader search architecture.

## Scope

### Required

Implement company-based client lookup through corporate email domains.

Example:

```text
Query:
Nevis Wealth

Client:
anton.batiaev@neviswealth.com

Expected:
Client is returned.
```

The matching logic must not depend on a stored `company` field, because the provided data model does not contain one.

Instead, derive the searchable company representation from the corporate email domain.

### Out of scope for this task

Do not decide or implement the following as part of this task unless explicitly added later:

- document search;
- synonym expansion;
- PostgreSQL Full Text Search for documents;
- client-scoped document search;
- tenant concepts;
- database-per-client;
- fuzzy company matching;
- external company/domain lookup services;
- LLM-based company recognition.

Whether client search should additionally support first name, last name, or full email lookup is a separate product decision and should not be silently added to this task.

## Expected Design

Keep client search behind its own application port.

```java
public interface ClientSearchPort {
    List<ClientSearchResult> search(ClientSearchQuery query);
}
```

The application layer should not depend on PostgreSQL-specific SQL.

A PostgreSQL adapter may implement the current search behavior.

```text
Search API / Application
          |
          v
   ClientSearchPort
          |
          v
PostgresClientSearchAdapter
          |
          v
      PostgreSQL
```

## Company Query Normalization

The core requirement is to normalize a human-readable company query into a representation that can be compared with a corporate email domain.

Example:

```text
"Nevis Wealth"
      |
      v
"neviswealth"
```

For:

```text
anton.batiaev@neviswealth.com
```

extract:

```text
neviswealth.com
```

and derive:

```text
neviswealth
```

Initial normalization should be deterministic and simple:

- lowercase;
- trim leading/trailing whitespace;
- remove spaces;
- ignore the top-level domain when comparing the company token to the email domain.

Do not introduce complex fuzzy matching in the first implementation.

## Data Access

The current implementation uses the shared PostgreSQL database.

Client data remains in the `clients` table.

No new `company` column is required for this task. Prefer deriving the company key from the corporate email domain.

## Search API Integration

The capability should be usable from the mandatory:

```http
GET /search?q=...
```

The global search facade may delegate client lookup to `ClientSearchPort`.

Client search should remain independently callable from the application layer so that a dedicated client-search endpoint can be added later without changing the search implementation.

Potential future endpoint:

```http
GET /clients?q=Nevis%20Wealth
```

This future endpoint is not required by the current home task.

## Tests

At minimum cover the following cases.

### Required business case

```text
email = anton.batiaev@neviswealth.com
query = Nevis Wealth
result = client returned
```

### Normalization

Equivalent company queries such as:

```text
Nevis Wealth
nevis wealth
NEVIS WEALTH
neviswealth
```

should produce the expected normalized representation.

### Non-match

```text
email = anton.batiaev@neviswealth.com
query = Other Company
result = client not returned
```

### Unexpected email values

Define deterministic behavior for malformed email data or domains that cannot produce a useful company key.

The search should not fail entirely because one client record contains unexpected data.

## Acceptance Criteria

The task is complete when:

1. `Nevis Wealth` finds a client with corporate email domain `neviswealth.com`.
2. Matching is based on corporate email information, not on a hard-coded company field.
3. Matching logic is isolated behind `ClientSearchPort`.
4. Company/domain normalization has focused unit tests.
5. PostgreSQL-specific implementation details do not leak into the application layer.
6. The implementation integrates with the mandatory `/search` endpoint.
7. No unrelated search functionality is added as part of this task.
8. First-name, last-name, and direct-email search behavior remains an explicit follow-up decision rather than an accidental side effect.
