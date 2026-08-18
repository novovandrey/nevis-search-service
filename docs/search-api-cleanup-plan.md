# Plan: Keep a Single Global Search API

## Goal

Align the implementation with the clarified requirement: keep only one global search endpoint that searches across both clients and documents.

## Changes

1. **Keep only the required search endpoint**
   - Keep:
     ```http
     GET /search?q=...
     ```
   - This endpoint remains the single entry point for search.

2. **Remove the additional client-specific GET endpoint**
   - Remove the extra endpoint that was added beyond the task requirements.
   - Remove its controller method and route mapping.

3. **Remove code used only by the deleted endpoint**
   - Remove service methods that exist only for the client-specific search flow.
   - Remove repository methods that are no longer used.
   - Remove DTOs or response models if they become unused.
   - Remove unused imports and other dead code.

4. **Remove tests for the deleted endpoint**
   - Remove controller tests for the extra GET endpoint.
   - Remove integration tests for that endpoint.
   - Remove service/repository tests only if they cover logic used exclusively by the deleted endpoint.

5. **Preserve the existing `/search` behavior**
   - Accept a single `q` query parameter.
   - Search both clients and documents.
   - Return the results in one combined list.
   - Do not introduce client-specific filtering into this endpoint unless required elsewhere.

6. **Update API documentation**
   - Remove the deleted endpoint from Swagger/OpenAPI.
   - Remove references to it from the README.
   - Remove obsolete examples related to client-specific search.
   - Ensure `/search?q=...` is documented as the single search API.

7. **Validate the cleanup**
   - Run the full test suite.
   - Verify the application builds successfully.
   - Check that there are no unused classes, methods, imports, or stale documentation references.
   - Avoid unrelated refactoring.

## Rationale

The clarified requirement is to expose a single global search endpoint that searches clients and documents and returns the results in one list. The additional client-specific search endpoint is therefore outside the required scope and should be removed.
