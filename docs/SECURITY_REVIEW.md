# Security Review

> Reviewed: 2026-08-18
>
> Scope: SQL injection, application access control, HTTP/API behavior, input handling, secrets,
> Docker Compose, and the deployed mini-PC smoke environment.
>
> Excluded by request: dependency and CVE/version analysis.

## Summary

No SQL injection vulnerability was found. All application SQL values are bound through `JdbcClient`
named parameters. The initial implementation is suitable for a local/demo environment, but is not
safe for a production or untrusted-network deployment until the high-priority access-control and
database-exposure findings below are resolved.

## Verified protections

- Every application value in `PostgresClientRepository`, `PostgresDocumentRepository`,
  `PostgresClientSearchAdapter`, and `PostgresQueryExpansionAdapter` is a named SQL parameter.
- `PostgresDocumentSearchAdapter` constructs only positional parameter names from internal numeric
  indexes; no user input is interpolated into SQL.
- `websearch_to_tsquery` receives each full-text term as a bound parameter.
- Query length, document content length, result limit, and pagination values are validated.
- The API returns generic `500` bodies without SQL, stack traces, or other server internals.
- The application container runs as non-root user `10001`.
- No committed secret was found. The known PostgreSQL password is an intentional development value
  in Compose, not an accidental secret.

## Active probes

The probes were run against the clean Docker Compose stack on the mini PC.

| Probe | Observed result | Result |
|---|---|---|
| Global `q=' OR 1=1 --` | `200 []` | no broadened result set |
| `q='; DROP TABLE clients;--` | `200 []` | payload treated as search text |
| `limit=1 OR 1=1` | `400` | integer binding rejected |
| Normal company search after probes | `200`, Anton returned | database remained intact |

## Findings

### HIGH — unauthenticated and unauthorized data access

The service does not implement authentication or authorization. Any network client can create
clients, create documents, and search globally. A client ID is an ownership value for document
creation, not an authorization check.

This is an explicit non-goal of the current home-task scope, so it is not a mismatch with the
architecture. It is nevertheless a production blocker when documents or client data are sensitive.

Evidence:

- `ClientController`, `DocumentController`, and `SearchController` expose all API routes without a
  security filter or identity check.
- Global search responses disclose matching client metadata and document metadata to any caller.

Recommended remediation:

1. Define the intended identity model and authorization rules before exposing the service beyond a
   trusted local environment.
2. Authenticate callers and authorize every client/document operation against the caller's allowed
   clients.
3. Continue requiring explicit `clientId` for document creation, but never treat it as proof of
   access rights.

### HIGH — PostgreSQL is exposed to the LAN with development credentials

Compose publishes PostgreSQL as `0.0.0.0:5432`, and the development username/password are present
in the Compose file. Connectivity from the review workstation to `192.168.1.87:5432` was confirmed.
An untrusted LAN client could attempt direct database access.

Evidence:

- `compose.yaml` publishes `5432:5432`.
- `compose.yaml` defines the development database password and gives the application the same
  credentials.

Recommended remediation:

1. Remove the PostgreSQL host-port mapping when it is not required; Compose services can use the
   internal `postgres:5432` network address.
2. If local host access is necessary, bind it only to `127.0.0.1`.
3. Supply non-development credentials through the deployment environment or a secret store.

### MEDIUM — runtime application uses the database owner/migration credentials

The runtime application and Flyway share the Compose database credentials. A compromise of the
application therefore gives an attacker more database power than a read/write search API needs.

Recommended remediation:

1. Use a schema owner/migration user for Flyway.
2. Use a separate runtime role restricted to the minimum `SELECT`/`INSERT` privileges required by
   the application.

### MEDIUM — no transport encryption or network boundary for the API

The deployed service is available over plain HTTP on the LAN. Client names, email addresses, and
document content can be observed or changed by a network attacker on an untrusted path. Swagger and
OpenAPI are also publicly reachable on that port.

Recommended remediation:

1. Put the API behind an HTTPS reverse proxy with a trusted certificate.
2. Limit ingress with host firewall rules or reverse-proxy allowlists.
3. Disable Swagger/OpenAPI outside development, or protect it with the same authorization model as
   the API.

### MEDIUM — request and response resource-exhaustion risk

There is no rate limiting and no transport-level JSON request-body limit. The application validates
document content only after Jackson has parsed it. A caller can therefore repeatedly send large
requests. Document content is validated only after Jackson has parsed it, so large JSON bodies can
still consume server memory before the application rejects them.

Evidence:

- `MAX_DOCUMENT_CONTENT_LENGTH` defaults to `1000000`.
- `DocumentResponse` includes the full document content in the document-creation response.

Recommended remediation:

1. Enforce request-body size at the reverse proxy or servlet boundary before JSON deserialization.
2. Add rate limiting and request concurrency limits at the reverse proxy/API gateway.
3. Keep global search results metadata-only; if a future document-content endpoint is added,
   protect it with explicit authorization and an appropriate response-size limit.

### LOW — unsupported methods and unknown paths are incorrectly handled as `500`

`PUT /clients` and an unknown URL both returned `500` rather than `405` and `404`. The generic
exception handler logs a full stack trace for each such request. This does not expose a stack trace
to the client, but allows inexpensive error-log amplification.

Evidence:

- `ApiExceptionHandler.handleUnexpected` catches all remaining exceptions and logs them as errors.

Recommended remediation:

1. Add explicit handlers for `HttpRequestMethodNotSupportedException` (`405`) and
   `NoResourceFoundException` (`404`).
2. Add integration tests for those two cases.
3. Keep unexpected errors at `500`, but avoid treating normal routing failures as unexpected.

### LOW — optional container hardening is not enabled

The application already runs as non-root, which is a useful baseline. The Compose application
container does not yet set a read-only root filesystem, drop Linux capabilities, or enable
`no-new-privileges`.

Recommended remediation:

1. Test `read_only: true`, a writable `tmpfs` only if the JVM needs it, `cap_drop: [ALL]`, and
   `security_opt: [no-new-privileges:true]` for the application service.
2. Add an application health check so Compose can distinguish a running process from a ready API.

## Deployment boundary

The architecture explicitly excludes authentication and authorization. Consequently, the current
service should only be exposed to a trusted development environment. Before storing real client
documents or opening access beyond that boundary, resolve the two HIGH findings first.
