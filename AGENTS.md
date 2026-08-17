# Repository Working Rules

## Technology and local development

- Use Java 25 and Spring Boot.
- PostgreSQL is the source of truth. The initial search implementation must use PostgreSQL Full Text Search.
- Apply all database schema changes through Flyway migrations.
- Keep the repository easy to run locally with Docker Compose.

## Architecture

- Keep application and business logic independent from PostgreSQL-specific search details by defining clear ports/interfaces and implementing PostgreSQL behavior in adapters.
- Prefer a simple, pragmatic architecture. Do not introduce abstractions unless they solve a concrete requirement.
- Documents belong to Clients. Every client-scoped document operation must receive and use an explicit `clientId`.
- Do not introduce Tenant concepts.
- Do not add functionality that is absent from the architecture plan or task requirements without documenting why it is necessary.

## Testing

- Cover core behavior and edge cases with tests.
- Test PostgreSQL-specific behavior against a real PostgreSQL instance. Prefer Testcontainers to H2.

## Change workflow

- Before implementing a substantial change, read the architecture plan and every applicable `AGENTS.md` file.
- If the architecture plan and the implementation disagree, flag the discrepancy explicitly. Do not silently choose one over the other.
