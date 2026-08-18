#!/usr/bin/env python3
"""
Black-box acceptance tests for the Nevis take-home API.

The script knows nothing about Java/Spring/PostgreSQL. It talks only to the
public HTTP API, similar to how a company's hidden integration tests could run.

Usage:
    python3 e2e/blackbox_api_tests.py
    python3 e2e/blackbox_api_tests.py --base-url http://localhost:8080

Exit codes:
    0 - all acceptance checks passed
    1 - at least one check failed
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


@dataclass
class Response:
    status: int
    body: Any
    raw: str


class AcceptanceFailure(AssertionError):
    pass


def parse_json(raw: str) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


def request(
    base_url: str,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
    timeout: float = 5.0,
) -> Response:
    data = None
    headers = {"Accept": "application/json"}

    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = Request(
        f"{base_url.rstrip('/')}{path}",
        data=data,
        headers=headers,
        method=method,
    )

    try:
        with urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return Response(resp.status, parse_json(raw), raw)
    except HTTPError as exc:
        raw = exc.read().decode("utf-8")
        return Response(exc.code, parse_json(raw), raw)


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def expect_status(response: Response, expected: int) -> None:
    check(
        response.status == expected,
        f"Expected HTTP {expected}, got {response.status}. Body: {response.raw}",
    )


def search_result_entities(results: Any) -> list[dict[str, Any]]:
    """
    Keeps the test mostly independent of the concrete SearchResult DTO.

    Supports:
      [
        {"type":"CLIENT","id":"...","email":"..."},
        {"type":"DOCUMENT","id":"...","title":"..."}
      ]

    and simple nested variants:
      [{"type":"CLIENT","client": {...}}]
      [{"type":"DOCUMENT","document": {...}}]
    """
    check(isinstance(results, list), f"/search must return a JSON array, got: {results!r}")

    entities: list[dict[str, Any]] = []

    for item in results:
        if not isinstance(item, dict):
            continue

        entities.append(item)

        for key in ("client", "document"):
            nested = item.get(key)
            if isinstance(nested, dict):
                merged = dict(nested)
                merged.setdefault("type", item.get("type"))
                entities.append(merged)

    return entities


def contains_entity_with_id(results: Any, entity_id: str) -> bool:
    return any(str(item.get("id")) == entity_id for item in search_result_entities(results))


def entity_with_id(results: Any, entity_id: str) -> dict[str, Any] | None:
    return next(
        (
            item
            for item in search_result_entities(results)
            if str(item.get("id")) == entity_id
        ),
        None,
    )


def contains_email(results: Any, email: str) -> bool:
    email_lower = email.lower()
    return any(
        isinstance(item.get("email"), str)
        and item["email"].lower() == email_lower
        for item in search_result_entities(results)
    )


def wait_until_ready(base_url: str, timeout_seconds: int = 60) -> None:
    deadline = time.time() + timeout_seconds
    last_error: Exception | None = None

    while time.time() < deadline:
        try:
            # q is required; any non-5xx answer means HTTP stack is alive.
            resp = request(base_url, "GET", "/search?q=readiness-probe", timeout=2.0)
            if resp.status < 500:
                return
        except (URLError, TimeoutError, ConnectionError) as exc:
            last_error = exc

        time.sleep(1)

    suffix = f": {last_error}" if last_error else ""
    raise AcceptanceFailure(f"Service not ready after {timeout_seconds}s{suffix}")


def run(base_url: str) -> None:
    token = uuid.uuid4().hex[:12]

    client_email = f"acceptance-{token}@neviswealth.com"
    document_title_prefix = f"Household Statement {token} "
    document_title = document_title_prefix + "a" * (255 - len(document_title_prefix))
    check(len(document_title) == 255, "Test setup must create a 255-character title")

    # Important: deliberately contains "utility bill", but NOT "address" or "proof".
    # This makes the synonym/similar-term check meaningful.
    document_content = (
        f"Utility bill reference {token}. "
        "Electricity account statement for the customer residence."
    )

    print(f"Target: {base_url}")
    print()

    wait_until_ready(base_url)
    print("PASS service becomes reachable")

    # ------------------------------------------------------------------
    # Contract validation
    # ------------------------------------------------------------------

    response = request(base_url, "GET", "/search")
    expect_status(response, 400)
    print("PASS GET /search requires q")

    response = request(base_url, "GET", f"/search?q={'a' * 256}")
    expect_status(response, 400)
    check(
        isinstance(response.body, dict)
        and response.body.get("message") == "Search query must not exceed 255 characters",
        f"GET /search must reject 256-character queries. Body: {response.raw}",
    )
    print("PASS GET /search rejects 256-character queries")

    response = request(
        base_url,
        "POST",
        "/clients",
        {
            "first_name": "Acceptance",
            # last_name intentionally omitted
            "email": client_email,
        },
    )
    expect_status(response, 400)
    print("PASS POST /clients validates required fields")

    # ------------------------------------------------------------------
    # Create client
    # ------------------------------------------------------------------

    response = request(
        base_url,
        "POST",
        "/clients",
        {
            "first_name": "Acceptance",
            "last_name": f"Tester-{token}",
            "email": client_email,
            "countryOfResidence": "Portugal",
        },
    )
    expect_status(response, 201)
    check(isinstance(response.body, dict), "POST /clients must return a JSON object")

    client_id_value = response.body.get("id")
    check(client_id_value is not None, f"Created client has no id. Body: {response.raw}")
    client_id = str(client_id_value)

    check(
        response.body.get("email") == client_email,
        f"Created client email mismatch. Body: {response.raw}",
    )
    print(f"PASS client created: {client_id}")

    # ------------------------------------------------------------------
    # Create document
    # ------------------------------------------------------------------

    response = request(
        base_url,
        "POST",
        f"/clients/{quote(client_id, safe='')}/documents",
        {
            "title": document_title,
            "content": document_content,
        },
    )
    expect_status(response, 201)
    check(isinstance(response.body, dict), "POST document must return a JSON object")

    document_id_value = response.body.get("id")
    check(document_id_value is not None, f"Created document has no id. Body: {response.raw}")
    document_id = str(document_id_value)

    returned_client_id = response.body.get("client_id")
    if returned_client_id is not None:
        check(
            str(returned_client_id) == client_id,
            f"Document client_id mismatch. Body: {response.raw}",
        )

    print(f"PASS document created: {document_id}")

    # ------------------------------------------------------------------
    # Query/title length consistency
    # ------------------------------------------------------------------

    response = request(base_url, "GET", f"/search?q={quote(document_title)}")
    expect_status(response, 200)
    check(
        contains_entity_with_id(response.body, document_id),
        (
            "A document with a 255-character title was not returned when searched "
            f"with its complete title. Body: {response.raw}"
        ),
    )
    print("PASS 255-character document title is accepted and searchable in full")

    # ------------------------------------------------------------------
    # Client/company search requirement
    # ------------------------------------------------------------------

    response = request(base_url, "GET", f"/search?q={quote('Nevis Wealth')}")
    expect_status(response, 200)

    check(
        contains_entity_with_id(response.body, client_id)
        or contains_email(response.body, client_email),
        (
            'Search for "Nevis Wealth" did not return the client whose corporate '
            f"email is {client_email}. Body: {response.raw}"
        ),
    )
    print('PASS "Nevis Wealth" finds client by corporate email domain')

    # ------------------------------------------------------------------
    # Exact document search
    # ------------------------------------------------------------------

    response = request(base_url, "GET", f"/search?q={quote(token)}")
    expect_status(response, 200)

    check(
        contains_entity_with_id(response.body, document_id),
        f"Exact document search did not return {document_id}. Body: {response.raw}",
    )
    print("PASS document is searchable through global /search")

    # ------------------------------------------------------------------
    # Similar-term / synonym requirement
    # ------------------------------------------------------------------

    response = request(base_url, "GET", f"/search?q={quote('address proof')}")
    expect_status(response, 200)

    check(
        contains_entity_with_id(response.body, document_id),
        (
            'Search for "address proof" did not return a document containing '
            f'"utility bill". Document id={document_id}. Body: {response.raw}'
        ),
    )
    matching_document = entity_with_id(response.body, document_id)
    check(
        matching_document is not None
        and matching_document.get("type") == "DOCUMENT",
        f"Synonym search result {document_id} is not typed as DOCUMENT. Body: {response.raw}",
    )
    check(
        matching_document.get("content") == document_content,
        (
            "Synonym search did not return the original stored document content. "
            f"Body: {response.raw}"
        ),
    )
    print('PASS "address proof" finds document containing "utility bill"')

    # ------------------------------------------------------------------
    # Basic empty-result behavior
    # ------------------------------------------------------------------

    impossible_query = f"zz-no-match-{uuid.uuid4().hex}"
    response = request(base_url, "GET", f"/search?q={quote(impossible_query)}")
    expect_status(response, 200)
    check(
        isinstance(response.body, list),
        f"No-match search must still return an array. Body: {response.raw}",
    )
    print("PASS no-match search returns HTTP 200 + JSON array")

    print()
    print("ALL BLACK-BOX ACCEPTANCE TESTS PASSED")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Running service URL (default: http://localhost:8080)",
    )
    args = parser.parse_args()

    try:
        run(args.base_url)
        return 0
    except (
        AcceptanceFailure,
        URLError,
        TimeoutError,
        ConnectionError,
    ) as exc:
        print()
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
