# Nevis Search Service — Final Deliverables Audit Report

> Audit date: 2026-08-19
> Audited application revision: `b5bc5fd`
> Source checklist: [`REQUIREMENTS_AUDIT.md`](REQUIREMENTS_AUDIT.md)

## Outcome

All mandatory deliverables are complete and verified against the Dockerized service on the mini-PC.
The implementation exposes only the required public endpoints:

```text
POST /clients
POST /clients/{id}/documents
GET  /search?q=...
```

Swagger UI and the generated OpenAPI document are the API-documentation deliverable. No static
OpenAPI copy is required.

## Source and Reproducibility

The tracked repository includes:

- Java 25 / Spring Boot source code and PostgreSQL adapters;
- Flyway migration `V1__initial_schema.sql` for schema and related-term seed data;
- `Dockerfile` and `compose.yaml`;
- Maven unit/integration tests and the standard-library Python black-box suite;
- `README.md`, architecture documentation, and generated OpenAPI configuration.

The resolved `nevis-smoke` Compose configuration publishes the application as:

```text
host 8080 -> container 8080/tcp
```

The final audit used the existing Compose project without deleting data:

```bash
docker compose -p nevis-smoke up -d --build --wait
```

`nevis-smoke-app-1` was running and healthy; `nevis-smoke-postgres-1` was running and healthy.
The persisted `nevis-smoke_postgres-data` volume remained present.

## Automated Verification

Executed on the Ubuntu mini-PC with Java 25, Docker 29.7.2, PostgreSQL 17.6 Testcontainers, and
the deployed Compose service:

```text
mvn -B test
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The suite includes 11 focused application/DTO tests and 8 PostgreSQL/API integration tests. The
integration suite created a fresh Testcontainers PostgreSQL database, validated Flyway, and applied
`V1__initial_schema.sql`.

The public HTTP black-box suite was compiled and run twice against the same non-empty database:

```text
python -m py_compile e2e/blackbox_api_tests.py
python e2e/blackbox_api_tests.py --base-url http://192.168.1.87:8080
python e2e/blackbox_api_tests.py --base-url http://192.168.1.87:8080
```

Both runs completed with `ALL BLACK-BOX ACCEPTANCE TESTS PASSED`. They cover client and document
creation, required/null/blank/type validation, length boundaries, invalid IDs, malformed JSON,
unsupported media types, query boundaries, search normalization, company-domain client search,
synonym expansion, complete document content, mixed result types, Unicode/punctuation robustness,
and repeatability.

## Live API Documentation Check

The deployed service returned:

```text
GET /v3/api-docs     200
GET /swagger-ui.html 200
openapi              3.1.0
info.title           API
info.version         1.0.0
```

The OpenAPI contract declares `POST /clients`, `POST /clients/{id}/documents`, and `GET /search`.
It documents document-search `content` as a string, matching the live response behavior.

## README Validation

`README.md` provides Docker Compose setup instructions, Maven test instructions, and complete
examples for client creation, document creation, `Nevis Wealth` client discovery, and `address
proof` finding a document containing `utility bill`. The black-box verification exercises the same
public workflow and confirms the documented behavior.

## Final Compliance Table

| Requirement | Status | Evidence | Fix made |
|---|---|---|---|
| Source code in a repository | PASS | tracked Java source, Flyway migration, Docker assets, tests, README, and docs on `main` | none |
| `POST /clients` | PASS | controller, integration tests, and two black-box runs | canonical JSON contract and strict string handling verified |
| `POST /clients/{id}/documents` | PASS | controller, integration tests, and two black-box runs | document content returned and boundary/error cases verified |
| `GET /search?q=...` | PASS | controller, PostgreSQL integration tests, and two black-box runs | 255-character query/title consistency and synonym behavior verified |
| PostgreSQL persistence and FTS | PASS | Flyway V1, PostgreSQL adapters, Testcontainers integration tests | none |
| Tests for core logic and edge cases | PASS | `mvn -B test`: 19/19; Python black-box suite passed twice | expanded public edge-case coverage |
| Docker Compose reproducibility | PASS | `docker compose -p nevis-smoke up -d --build --wait`; healthy app and PostgreSQL | none |
| Service exposed on port `8080` | PASS | resolved Compose mapping `8080 -> 8080/tcp`; live API checks | fixed standard mapping documented |
| README setup instructions | PASS | `README.md` Run locally, Tests, Configuration sections | none |
| README search examples and responses | PASS | `README.md` Example workflow; black-box search verification | complete document content example verified |
| API documentation | PASS | Swagger UI `/swagger-ui.html`, OpenAPI `/v3/api-docs`, integration contract assertions | generated OpenAPI reflects the live API |
| Optional summary | OPTIONAL | intentionally out of scope | none |

## Completion

Every mandatory requirement is `PASS`. The two task-defining acceptance scenarios were verified
end-to-end:

```text
Nevis Wealth  -> client with neviswealth.com corporate domain
address proof -> DOCUMENT containing utility bill and its original stored content
```

No endpoint, ranking, schema, or migration change was needed for this final deliverables audit.
