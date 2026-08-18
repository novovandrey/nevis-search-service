# Nevis Home Task — Requirements Audit Report

> Audit date: 2026-08-18
>
> Source checklist: [`REQUIREMENTS_AUDIT.md`](REQUIREMENTS_AUDIT.md)
>
> Scope: existing implementation plus the smallest fixes needed for the mandatory contract

## Outcome

The code, schema, tests, generated OpenAPI contract, and documentation satisfy the mandatory
requirements. The optional document-summary feature remains intentionally unimplemented.

The audit found four contract/documentation gaps:

1. client name fields and document metadata were serialized in camelCase instead of the supplied
   wire names `first_name`, `last_name`, `client_id`, and `created_at`;
2. generated OpenAPI did not declare the actual success and error status codes;
3. the README did not include response bodies for both creation calls or a complete global
   `address proof` to `utility bill` example;
4. an unsupported request media type was caught by the generic exception handler and incorrectly
   returned `500` instead of `415`.

The fixes are limited to Jackson/OpenAPI annotations, contract assertions, and documentation.
Application services, ports/adapters, PostgreSQL search behavior, and the database schema were not
redesigned. Legacy `firstName` and `lastName` request fields remain accepted as aliases, while the
documented canonical contract is snake_case.

## Code and test evidence

- Client creation: `ClientController.create`, `ClientService.create`, and
  `PostgresClientRepository.save`.
- Document creation and ownership: `DocumentController.create`, `DocumentService.create`, and
  `PostgresDocumentRepository.save`.
- Mandatory global search: `SearchController.search` and `SearchService.search`.
- Replaceable search boundaries: `ClientSearchPort`, `DocumentSearchPort`, and
  `QueryExpansionPort`, implemented by adapters under `infrastructure.postgres`.
- PostgreSQL schema and reproducible related terms:
  `src/main/resources/db/migration/V1__initial_schema.sql`.
- Real-database and API behavior: `NevisPostgresIntegrationTest`, especially
  `migrationsCreateGeneratedSearchVectorAndForeignKey`,
  `clientSearchMatchesOnlyNormalizedCompanyDomainAndIgnoresUnexpectedEmails`,
  `businessTermsUseOrSemanticsAndClientScopeNeverLeaksDocuments`,
  `fullTextSearchUsesStemmingRankingAndDeterministicNoResultBehavior`,
  `apiCoversCreationValidationUnknownClientScopedAndGlobalSearch`, and
  `openApiDocumentsJsonContractAndActualResponseStatuses`.
- Focused application tests: `ClientSearchQueryNormalizerTest`, `QueryNormalizerTest`,
  `QueryExpanderTest`, `DocumentServiceTest`, and `SearchServiceTest`.

## Automated verification

Executed on the Ubuntu mini PC with Java 25, Docker 29.7.2, PostgreSQL 17.6 Testcontainers, and an
empty test schema:

```text
mvn verify
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The 17 tests consist of 11 focused unit tests and 6 PostgreSQL/API integration tests. Flyway
validated and applied `V1__initial_schema.sql` to an empty PostgreSQL schema during the run.

## Docker Compose and end-to-end evidence

The old smoke stack and its PostgreSQL volume were removed first. The final image and empty
database were then created with:

```text
APP_HOST_PORT=8080 docker compose -p nevis-smoke up -d --build --wait
```

Both `nevis-smoke-app-1` and `nevis-smoke-postgres-1` became healthy. Flyway created the empty
schema at version V1, the application listened on container port `8080`, and Compose published it
as 8080`.

Observed final HTTP results against the clean stack:

```text
POST /clients                                      201; canonical snake_case response
POST /clients/{id}/documents                       201; correct client_id and created_at
GET  /search?q=Nevis%20Wealth                       200; Anton/client returned
GET  /search?q=address%20proof                      200; May Utility Bill/document returned
GET  /v3/api-docs                                   200
POST /clients with application/octet-stream         415; consistent ApiError response
```

## Final compliance table

| Requirement | Status | Evidence | Fix made |
|---|---|---|---|
| POST /clients | PASS | `ClientController.create`; `ClientService.create`; `PostgresClientRepository.save`; `apiCoversCreationValidationUnknownClientScopedAndGlobalSearch` | canonical `first_name`/`last_name` JSON contract added with compatibility aliases |
| POST /clients/{id}/documents | PASS | `DocumentController.create`; `DocumentService.create`; `PostgresDocumentRepository.save`; API integration test | canonical `client_id`/`created_at` response fields and assertions added |
| GET /search?q= | PASS | `SearchController.search`; `SearchService.search`; API integration test covers required, blank, matching, and empty queries | missing-`q` assertion added |
| Company search via corporate email | PASS | `PostgresClientSearchAdapter`; `ClientSearchQueryNormalizerTest`; `clientSearchMatchesOnlyNormalizedCompanyDomainAndIgnoresUnexpectedEmails`; API E2E assertion | none |
| Similar-term document search | PASS | `QueryExpander`; `PostgresQueryExpansionAdapter`; `PostgresDocumentSearchAdapter`; mapping/isolation and API E2E tests | strengthened global API assertion for `Utility Bill` |
| REST/HTTP codes | PASS | controllers; `ApiExceptionHandler`; API integration test | actual `200`/`201`/`400`/`404`/`415`/`500` responses documented in OpenAPI; unsupported media now returns `415` |
| PostgreSQL persistence | PASS | PostgreSQL JDBC adapters; Flyway V1; migration integration test | none |
| PostgreSQL FTS | PASS | generated weighted `search_vector`, GIN index, `PostgresDocumentSearchAdapter`; stemming/ranking integration test | none |
| Tests for core logic and edge cases | PASS | 17 tests: 11 unit and 6 PostgreSQL/API integration; `mvn verify` succeeds | JSON-contract and generated-OpenAPI integration coverage added |
| Docker Compose | PASS | clean image, network, PostgreSQL volume, Flyway migration, healthy containers, and E2E smoke on mini PC | added configurable host-port override while preserving the required default |
| Port 8080 | PASS | application verified listening on container `8080`; `compose.yaml` defaults host mapping to `8080`; `${APP_HOST_PORT:-8080}:8080` supports the occupied mini-PC host without changing the default contract |
| README setup instructions | PASS | `README.md` — Run locally, Tests, Configuration | none |
| README examples | PASS | `README.md` — Example workflow | added creation responses and complete related-term global-search example; aligned JSON names |
| API documentation | PASS | Swagger UI `/swagger-ui.html`; OpenAPI `/v3/api-docs`; `openApiDocumentsJsonContractAndActualResponseStatuses` | response codes/schemas explicitly annotated and tested |
| Optional summary | OPTIONAL | N/A; explicitly listed as a non-goal | none |
