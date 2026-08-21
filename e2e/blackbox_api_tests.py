#!/usr/bin/env python3
"""Black-box acceptance tests for the public Nevis HTTP API."""

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


# These values mirror the current public validation contract.
FIRST_NAME_MIN = 1
FIRST_NAME_MAX = 100
LAST_NAME_MIN = 1
LAST_NAME_MAX = 100
EMAIL_MIN = 1
EMAIL_MAX = 254
COUNTRY_MAX = 100
TITLE_MIN = 1
TITLE_MAX = 255
QUERY_MAX = 255
OMIT = object()


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
    payload: Any | None = None,
    *,
    raw_body: str | bytes | None = None,
    content_type: str | None = None,
    timeout: float = 5.0,
) -> Response:
    data: bytes | None = None
    headers = {"Accept": "application/json"}

    if raw_body is not None:
        data = raw_body.encode("utf-8") if isinstance(raw_body, str) else raw_body
    elif payload is not None:
        data = json.dumps(payload).encode("utf-8")

    if data is not None:
        headers["Content-Type"] = content_type or "application/json"

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


def expect_status(response: Response, expected: int, label: str = "request") -> None:
    check(
        response.status == expected,
        f"{label}: expected HTTP {expected}, got {response.status}. Body: {response.raw}",
    )


def expect_json_object(response: Response, label: str) -> dict[str, Any]:
    check(
        isinstance(response.body, dict),
        f"{label}: expected a JSON object, got {response.raw}",
    )
    return response.body


def expect_json_array(response: Response, label: str) -> list[Any]:
    check(
        isinstance(response.body, list),
        f"{label}: expected a JSON array, got {response.raw}",
    )
    return response.body


def expect_error(response: Response, label: str, expected: int | None = None) -> None:
    if expected is None:
        check(
            400 <= response.status < 500,
            f"{label}: expected a 4xx response, got {response.status}. Body: {response.raw}",
        )
    else:
        expect_status(response, expected, label)

    expect_json_object(response, label)
    lowered = response.raw.lower()
    for forbidden in ("traceback", "exception", "stack trace", "jdbc", "postgresql", "select "):
        check(
            forbidden not in lowered,
            f"{label}: error response exposes internal detail {forbidden!r}. Body: {response.raw}",
        )


def string_of_length(length: int, character: str = "a") -> str:
    check(len(character) == 1, "Boundary helper requires one character")
    return character * length


def unique_email(token: str, suffix: str = "") -> str:
    return f"acceptance-{token}{suffix}@example.com"


def email_of_length(length: int, token: str, suffix: str = "") -> str:
    # Keep the local part within the RFC/validator limit while exercising the
    # DTO's total @Size(max = 254) boundary with a long, valid domain.
    domain = "@" + "a" * 63 + "." + "b" * 63 + "." + "c" * 61
    local_prefix = f"acceptance{suffix}{token}"
    local_length = length - len(domain)
    check(local_length > len(local_prefix), f"Email length {length} is too short for test prefix")
    return local_prefix + "a" * (local_length - len(local_prefix)) + domain


def valid_client_payload(token: str, *, country: str | None | object = "Portugal") -> dict[str, Any]:
    payload: dict[str, Any] = {
        "first_name": "Acceptance",
        "last_name": f"Tester-{token}",
        "email": f"acceptance-{token}@neviswealth.com",
    }
    if country is not OMIT:
        payload["countryOfResidence"] = country
    return payload


def create_client(base_url: str, token: str, payload: dict[str, Any] | None = None) -> tuple[str, dict[str, Any]]:
    body = payload or valid_client_payload(token)
    response = request(base_url, "POST", "/clients", body)
    expect_status(response, 201, "create client")
    result = expect_json_object(response, "create client")
    client_id = result.get("id")
    check(client_id is not None, f"create client: response has no id. Body: {response.raw}")
    return str(client_id), result


def document_path(client_id: str) -> str:
    return f"/clients/{quote(client_id, safe='')}/documents"


def create_document(
    base_url: str,
    client_id: str,
    title: str,
    content: str,
) -> tuple[str, dict[str, Any]]:
    response = request(
        base_url,
        "POST",
        document_path(client_id),
        {"title": title, "content": content},
    )
    expect_status(response, 201, "create document")
    result = expect_json_object(response, "create document")
    document_id = result.get("id")
    check(document_id is not None, f"create document: response has no id. Body: {response.raw}")
    return str(document_id), result


def search_request(base_url: str, query: str) -> Response:
    return request(base_url, "GET", f"/search?q={quote(query, safe='')}")


def search_result_entities(results: Any) -> list[dict[str, Any]]:
    """Return public result objects, tolerating simple nested response variants."""
    check(isinstance(results, list), f"/search must return a JSON array, got: {results!r}")

    entities: list[dict[str, Any]] = []
    for item in results:
        check(isinstance(item, dict), f"/search result must be an object, got: {item!r}")
        entities.append(item)
        for key in ("client", "document"):
            nested = item.get(key)
            if isinstance(nested, dict):
                merged = dict(nested)
                merged.setdefault("type", item.get("type"))
                entities.append(merged)
    return entities


def assert_search_shape(response: Response, label: str) -> list[dict[str, Any]]:
    results = expect_json_array(response, label)
    for index, item in enumerate(results):
        check(isinstance(item, dict), f"{label}: result {index} is not an object: {item!r}")
        result_type = item.get("type")
        check(result_type in ("CLIENT", "DOCUMENT"), f"{label}: invalid result type: {item!r}")
        check(item.get("id") is not None, f"{label}: result has no id: {item!r}")
        if result_type == "DOCUMENT":
            check(isinstance(item.get("content"), str), f"{label}: document lacks string content: {item!r}")
        else:
            check("content" not in item, f"{label}: client result unexpectedly contains content: {item!r}")
    return search_result_entities(results)


def contains_entity_with_id(results: Any, entity_id: str) -> bool:
    return any(str(item.get("id")) == entity_id for item in search_result_entities(results))


def entity_with_id(results: Any, entity_id: str) -> dict[str, Any] | None:
    return next(
        (item for item in search_result_entities(results) if str(item.get("id")) == entity_id),
        None,
    )


def contains_email(results: Any, email: str) -> bool:
    email_lower = email.lower()
    return any(
        isinstance(item.get("email"), str) and item["email"].lower() == email_lower
        for item in search_result_entities(results)
    )


def wait_until_ready(base_url: str, timeout_seconds: int = 60) -> None:
    deadline = time.time() + timeout_seconds
    last_error: Exception | None = None
    while time.time() < deadline:
        try:
            response = search_request(base_url, "readiness-probe")
            if response.status < 500:
                return
        except (URLError, TimeoutError, ConnectionError) as exc:
            last_error = exc
        time.sleep(1)
    suffix = f": {last_error}" if last_error else ""
    raise AcceptanceFailure(f"Service not ready after {timeout_seconds}s{suffix}")


def test_client_contract(base_url: str, token: str) -> tuple[str, dict[str, Any]]:
    client_id, client_response = create_client(base_url, token)
    for field in ("id", "first_name", "last_name", "email"):
        check(field in client_response, f"client response lacks public field {field}: {client_response}")
    check(client_response["first_name"] == "Acceptance", "client first_name mismatch")
    check(client_response["last_name"] == f"Tester-{token}", "client last_name mismatch")
    print(f"PASS client happy path and response contract: {client_id}")

    omitted_id, _ = create_client(
        base_url,
        token,
        valid_client_payload(token, country=OMIT),
    )
    print(f"PASS optional countryOfResidence may be omitted: {omitted_id}")

    null_id, _ = create_client(
        base_url,
        token,
        {**valid_client_payload(token), "email": unique_email(token, "-country-null"), "countryOfResidence": None},
    )
    print(f"PASS optional countryOfResidence accepts null: {null_id}")

    for field in ("first_name", "last_name", "email"):
        payload = valid_client_payload(token)
        payload["email"] = unique_email(token, f"-missing-{field}")
        del payload[field]
        expect_error(
            request(base_url, "POST", "/clients", payload),
            f"missing client field {field}",
            400,
        )
    print("PASS each required client field is validated independently")

    for field in ("first_name", "last_name", "email"):
        payload = valid_client_payload(token)
        payload["email"] = unique_email(token, f"-null-{field}")
        payload[field] = None
        expect_error(request(base_url, "POST", "/clients", payload), f"null client field {field}", 400)
    print("PASS null required client fields are rejected")

    for field in ("first_name", "last_name", "email"):
        for value in ("", "   "):
            payload = valid_client_payload(token)
            payload["email"] = unique_email(token, f"-blank-{field}-{len(value)}")
            payload[field] = value
            expect_error(
                request(base_url, "POST", "/clients", payload),
                f"blank client field {field!r}",
                400,
            )
    print("PASS blank and whitespace-only required client fields are rejected")

    invalid_email = valid_client_payload(token)
    invalid_email["email"] = f"not-an-email-{token}"
    expect_error(request(base_url, "POST", "/clients", invalid_email), "invalid email", 400)
    print("PASS invalid email is rejected")

    wrong_types = [123, True, 12.5, ["Acceptance"], {"value": "Acceptance"}]
    for index, value in enumerate(wrong_types):
        payload = valid_client_payload(token)
        payload["email"] = unique_email(token, f"-type-{index}")
        payload["first_name"] = value
        expect_error(request(base_url, "POST", "/clients", payload), f"wrong first_name type {index}", 400)
    print("PASS non-string client field values are rejected")

    first_max = valid_client_payload(token)
    first_max.update({"first_name": string_of_length(FIRST_NAME_MAX), "email": unique_email(token, "-first-100")})
    create_client(base_url, token, first_max)
    first_over = {**first_max, "first_name": string_of_length(FIRST_NAME_MAX + 1), "email": unique_email(token, "-first-101")}
    expect_error(request(base_url, "POST", "/clients", first_over), "first_name max+1", 400)
    print("PASS first_name length boundary 100/101")

    last_max = valid_client_payload(token)
    last_max.update({"last_name": string_of_length(LAST_NAME_MAX), "email": unique_email(token, "-last-100")})
    create_client(base_url, token, last_max)
    last_over = {**last_max, "last_name": string_of_length(LAST_NAME_MAX + 1), "email": unique_email(token, "-last-101")}
    expect_error(request(base_url, "POST", "/clients", last_over), "last_name max+1", 400)
    print("PASS last_name length boundary 100/101")

    email_max = valid_client_payload(token)
    email_max["email"] = email_of_length(EMAIL_MAX, token, "-email-max-")
    create_client(base_url, token, email_max)
    email_over = {**email_max, "email": email_of_length(EMAIL_MAX + 1, token, "-email-over-")}
    expect_error(request(base_url, "POST", "/clients", email_over), "email max+1", 400)
    print("PASS email length boundary 254/255")

    country_max = valid_client_payload(token)
    country_max.update({"countryOfResidence": string_of_length(COUNTRY_MAX), "email": unique_email(token, "-country-100")})
    create_client(base_url, token, country_max)
    country_over = {**country_max, "countryOfResidence": string_of_length(COUNTRY_MAX + 1), "email": unique_email(token, "-country-101")}
    expect_error(request(base_url, "POST", "/clients", country_over), "countryOfResidence max+1", 400)
    print("PASS countryOfResidence length boundary 100/101")

    expect_error(
        request(base_url, "POST", "/clients", raw_body='{"first_name":', content_type="application/json"),
        "malformed client JSON",
        400,
    )
    print("PASS malformed client JSON returns 400")
    return client_id, client_response


def test_document_contract(base_url: str, client_id: str, token: str) -> tuple[str, str, str, str]:
    document_content = (
        f"Utility bill reference {token}. "
        "Electricity account statement for the customer residence."
    )
    normal_id, normal_response = create_document(
        base_url,
        client_id,
        f"Utility Bill {token}",
        document_content,
    )
    for field in ("id", "client_id", "title", "content", "created_at"):
        check(field in normal_response, f"document response lacks public field {field}: {normal_response}")
    check(str(normal_response["client_id"]) == client_id, "document client_id mismatch")
    check(normal_response["content"] == document_content, "document content mismatch")
    print(f"PASS document happy path and response contract: {normal_id}")

    address_id, _ = create_document(
        base_url,
        client_id,
        "Manual Browser Utility Bill",
        "Electricity statement for the current residential address",
    )

    for field in ("title", "content"):
        payload = {"title": f"Missing {token}", "content": "abc"}
        if field == "content":
            payload = {"title": f"Missing {token}", "content": "abc"}
        del payload[field]
        expect_error(
            request(base_url, "POST", document_path(client_id), payload),
            f"missing document field {field}",
            400,
        )
    print("PASS each required document field is validated independently")

    for field in ("title", "content"):
        payload = {"title": f"Null {token}", "content": "abc"}
        payload[field] = None
        expect_error(request(base_url, "POST", document_path(client_id), payload), f"null document field {field}", 400)
    print("PASS null document fields are rejected")

    for field in ("title", "content"):
        for value in ("", "   "):
            payload = {"title": f"Blank {token}", "content": "abc"}
            payload[field] = value
            expect_error(request(base_url, "POST", document_path(client_id), payload), f"blank document field {field}", 400)
    print("PASS blank and whitespace-only document fields are rejected")

    wrong_document_types = [
        ("title", 123),
        ("title", True),
        ("title", 12.5),
        ("title", ["title"]),
        ("title", {"value": "title"}),
        ("content", 123),
        ("content", ["content"]),
        ("content", {"value": "content"}),
    ]
    for index, (field, value) in enumerate(wrong_document_types):
        payload: dict[str, Any] = {"title": f"Type {token} {index}", "content": "abc"}
        payload[field] = value
        expect_error(request(base_url, "POST", document_path(client_id), payload), f"wrong document {field} type {index}", 400)
    print("PASS non-string document field values are rejected")

    title_254_prefix = f"T{token}"
    title_254 = title_254_prefix + string_of_length(TITLE_MAX - 1 - len(title_254_prefix), "x")
    check(len(title_254) == TITLE_MAX - 1, "title 254 setup failed")
    create_document(base_url, client_id, title_254, f"Title boundary 254 {token}")

    title_255_prefix = f"Household Statement {token} "
    title_255 = title_255_prefix + string_of_length(TITLE_MAX - len(title_255_prefix), "a")
    check(len(title_255) == TITLE_MAX, "title 255 setup failed")
    max_id, _ = create_document(base_url, client_id, title_255, document_content)

    title_256 = title_255 + "x"
    expect_error(
        request(base_url, "POST", document_path(client_id), {"title": title_256, "content": "too long"}),
        "title max+1",
        400,
    )
    print("PASS document title length boundary 254/255/256")

    non_existing_client = str(uuid.uuid4())
    expect_error(
        request(base_url, "POST", document_path(non_existing_client), {"title": "Missing client", "content": "abc"}),
        "non-existing client",
        404,
    )
    expect_error(
        request(base_url, "POST", document_path("not-a-uuid"), {"title": "Invalid id", "content": "abc"}),
        "invalid client UUID",
        400,
    )
    print("PASS document client-id validation returns 404/400")

    expect_error(
        request(base_url, "POST", document_path(client_id), raw_body='{"title":', content_type="application/json"),
        "malformed document JSON",
        400,
    )
    expect_error(
        request(
            base_url,
            "POST",
            document_path(client_id),
            raw_body="title=plain-text",
            content_type="text/plain",
        ),
        "unsupported document content type",
        415,
    )
    print("PASS malformed document JSON and unsupported content type return 4xx")
    return normal_id, address_id, max_id, document_content


def test_search_contract(
    base_url: str,
    client_id: str,
    address_document_id: str,
    max_document_id: str,
    max_title: str,
    document_content: str,
    token: str,
) -> None:
    response = search_request(base_url, "")
    expect_error(response, "empty search query", 400)
    response = search_request(base_url, "   ")
    expect_error(response, "blank search query", 400)
    print("PASS search rejects empty and whitespace-only q")

    for length in (QUERY_MAX - 1, QUERY_MAX):
        response = search_request(base_url, string_of_length(length, "q"))
        expect_status(response, 200, f"search q length {length}")
        assert_search_shape(response, f"search q length {length}")
    expect_error(search_request(base_url, string_of_length(QUERY_MAX + 1, "q")), "search q max+1", 400)
    print("PASS search query length boundary 254/255/256")

    response = search_request(base_url, token)
    expect_status(response, 200, "exact document search")
    assert_search_shape(response, "exact document search")
    check(contains_entity_with_id(response.body, max_document_id), f"exact search did not return {max_document_id}: {response.raw}")
    print("PASS exact document search")

    response = search_request(base_url, max_title)
    expect_status(response, 200, "full title search")
    assert_search_shape(response, "full title search")
    check(contains_entity_with_id(response.body, max_document_id), f"full title search did not return {max_document_id}: {response.raw}")
    print("PASS 255-character title is searchable with complete q")

    response = search_request(base_url, "address")
    expect_status(response, 200, "weighted hybrid ranking")
    entities = assert_search_shape(response, "weighted hybrid ranking")
    address_rank = next(
        (index for index, item in enumerate(entities) if str(item.get("id")) == address_document_id),
        None,
    )
    boundary_rank = next(
        (
            index
            for index, item in enumerate(entities)
            if item.get("type") == "DOCUMENT"
            and str(item.get("content", "")).startswith("Title boundary 254")
        ),
        None,
    )
    check(address_rank is not None, f"literal address document is missing: {response.raw}")
    check(
        boundary_rank is None or address_rank < boundary_rank,
        f"semantic-only boundary document outranked literal address match: {response.raw}",
    )
    print("PASS weighted RRF ranks literal address match above boundary document")

    response = search_request(base_url, "Nevis Wealth")
    expect_status(response, 200, "company-domain search")
    entities = assert_search_shape(response, "company-domain search")
    check(contains_entity_with_id(response.body, client_id) or contains_email(response.body, "neviswealth.com"), f"company-domain search did not return client: {response.raw}")
    check(any(item.get("type") == "CLIENT" for item in entities), f"company-domain result is not typed CLIENT: {response.raw}")
    print("PASS client company-domain search")

    response = search_request(base_url, "address proof")
    expect_status(response, 200, "synonym search")
    assert_search_shape(response, "synonym search")
    matching = entity_with_id(response.body, max_document_id)
    check(matching is not None, f"synonym search did not return document {max_document_id}: {response.raw}")
    check(matching.get("type") == "DOCUMENT", f"synonym result is not DOCUMENT: {response.raw}")
    check(matching.get("content") == document_content, f"synonym result content mismatch: {response.raw}")
    check("address proof" not in document_content.lower(), "synonym fixture accidentally contains the searched phrase")
    print('PASS synonym search returns original DOCUMENT content')

    combined_title = f"Nevis Wealth {token}"
    combined_id, _ = create_document(base_url, client_id, combined_title, f"Combined result {token}")
    response = search_request(base_url, "Nevis Wealth")
    expect_status(response, 200, "combined result search")
    entities = assert_search_shape(response, "combined result search")
    check(any(item.get("type") == "CLIENT" for item in entities), f"combined search lacks CLIENT: {response.raw}")
    check(any(item.get("type") == "DOCUMENT" for item in entities), f"combined search lacks DOCUMENT: {response.raw}")
    check(contains_entity_with_id(response.body, combined_id), f"combined search lacks document {combined_id}: {response.raw}")
    print("PASS global search returns both CLIENT and DOCUMENT results")

    for query in ("utility bill", "UTILITY BILL", "Utility Bill", "  utility bill  "):
        response = search_request(base_url, query)
        expect_status(response, 200, f"search normalization {query!r}")
        assert_search_shape(response, f"search normalization {query!r}")
        check(contains_entity_with_id(response.body, max_document_id), f"normalized search did not return document for {query!r}: {response.raw}")
    print("PASS search case and whitespace normalization")

    for query, label in ((f"Привет {token}", "unicode search"), ("bank-statement", "punctuation search")):
        response = search_request(base_url, query)
        expect_status(response, 200, label)
        assert_search_shape(response, label)
    print("PASS Unicode and punctuation search remain valid HTTP/JSON")

    impossible_query = "volcanic magma beneath tectonic plates"
    response = search_request(base_url, impossible_query)
    expect_status(response, 200, "no-result search")
    results = assert_search_shape(response, "no-result search")
    check(results == [], f"no-result search should return an empty array: {response.raw}")
    print("PASS no-result search returns HTTP 200 and an empty array")


def test_fuzzy_company_search(base_url: str, token: str) -> None:
    exact_id, _ = create_client(
        base_url,
        token,
        {
            "first_name": "Hewlett",
            "last_name": "Exact",
            "email": f"exact-{token}@hewlettpackard.com",
            "countryOfResidence": "UK",
        },
    )
    fuzzy_id, _ = create_client(
        base_url,
        token,
        {
            "first_name": "Hewlett",
            "last_name": "Typo",
            "email": f"typo-{token}@hewlettpackarrd.io",
            "countryOfResidence": "UK",
        },
    )

    response = search_request(base_url, "Hewlett Packard")
    expect_status(response, 200, "fuzzy company-domain search")
    entities = assert_search_shape(response, "fuzzy company-domain search")
    clients = [item for item in entities if item.get("type") == "CLIENT"]
    exact_index = next((index for index, item in enumerate(clients) if str(item.get("id")) == exact_id), None)
    fuzzy_index = next((index for index, item in enumerate(clients) if str(item.get("id")) == fuzzy_id), None)
    check(exact_index is not None, f"exact Hewlett Packard client is missing: {response.raw}")
    check(fuzzy_index is not None, f"typo-tolerant Hewlett Packard client is missing: {response.raw}")
    check(exact_index < fuzzy_index, f"exact company match did not rank above fuzzy match: {response.raw}")
    print("PASS pg_trgm company search finds typo and keeps exact result first")


def test_http_robustness(base_url: str, client_id: str) -> None:
    response = request(
        base_url,
        "POST",
        "/clients",
        raw_body="plain text",
        content_type="text/plain",
    )
    expect_error(response, "unsupported client content type", 415)
    print("PASS unsupported client content type returns 415")


def test_semantic_search(base_url: str, client_id: str) -> None:
    semantic_document_id, _ = create_document(
        base_url,
        client_id,
        "Monthly electricity statement",
        "The customer receives a monthly electricity statement for the apartment at 10 King Street.",
    )
    response = search_request(base_url, "evidence of where the customer lives")
    expect_status(response, 200, "semantic document search")
    assert_search_shape(response, "semantic document search")
    check(
        contains_entity_with_id(response.body, semantic_document_id),
        f"semantic search did not return document {semantic_document_id}: {response.raw}",
    )
    print("PASS semantic search finds a document without lexical overlap or an explicit term mapping")

    late_content = (
        "Cooking archive notes discuss tomatoes basil pasta olive oil and kitchen equipment. " * 90
        + "\n\nThe customer receives a monthly electricity statement for the apartment at 10 King Street."
    )
    late_document_id, late_response = create_document(
        base_url,
        client_id,
        "Monthly electricity statement archive",
        late_content,
    )
    check(late_response["content"] == late_content, "chunked document response did not preserve full content")
    response = search_request(base_url, "evidence of where the customer lives")
    expect_status(response, 200, "late-window semantic document search")
    assert_search_shape(response, "late-window semantic document search")
    check(
        contains_entity_with_id(response.body, late_document_id),
        f"semantic search did not find late document content {late_document_id}: {response.raw}",
    )
    print("PASS semantic search finds a concept after the first embedding context window")


def run(base_url: str) -> None:
    token = uuid.uuid4().hex[:12]
    print(f"Target: {base_url}")
    print(f"Contract limits: names={FIRST_NAME_MAX}, email={EMAIL_MAX}, country={COUNTRY_MAX}, title/q={TITLE_MAX}")
    print()

    wait_until_ready(base_url)
    print("PASS service becomes reachable")

    client_id, _ = test_client_contract(base_url, token)
    normal_document_id, address_document_id, max_document_id, document_content = test_document_contract(
        base_url, client_id, token
    )
    check(normal_document_id != max_document_id, "document fixtures unexpectedly share an id")

    max_title_prefix = f"Household Statement {token} "
    max_title = max_title_prefix + string_of_length(TITLE_MAX - len(max_title_prefix), "a")
    test_search_contract(
        base_url,
        client_id,
        address_document_id,
        max_document_id,
        max_title,
        document_content,
        token,
    )
    test_fuzzy_company_search(base_url, token)
    test_semantic_search(base_url, client_id)
    test_http_robustness(base_url, client_id)

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
    except (AcceptanceFailure, URLError, TimeoutError, ConnectionError) as exc:
        print()
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
