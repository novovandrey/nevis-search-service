from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path


SEED = 20260821
PLACEMENT_TAGS = (
    "start",
    "middle",
    "end",
    "before_boundary",
    "after_boundary",
    "oversized_paragraph",
    "token_fallback_sentence",
    "misleading_title",
    "multi_topic",
    "duplicate_near_duplicate",
    "multiple_relevant_chunks",
)
DOMAINS = {
    "residency": (
        "electricity account at a residential address",
        "signed tenancy agreement for a current home",
        "municipal tax notice naming the occupant",
        "water service statement for the apartment",
        "landlord confirmation of permanent residence",
        "internet service invoice tied to the dwelling",
    ),
    "identity": (
        "biometric passport identity record",
        "national identity card verification",
        "birth certificate registry extract",
        "driving licence identity confirmation",
        "citizenship certificate evidence",
        "legal name change deed",
    ),
    "finance": (
        "audited annual revenue statement",
        "bank account ownership confirmation",
        "mortgage repayment schedule",
        "investment portfolio valuation",
        "source of funds declaration",
        "business loan covenant report",
    ),
    "employment": (
        "signed permanent employment contract",
        "recent payroll income statement",
        "employer reference confirming role",
        "workplace pension contribution record",
        "annual performance appraisal",
        "termination and severance agreement",
    ),
    "health": (
        "vaccination history certificate",
        "specialist referral appointment letter",
        "prescription medication summary",
        "hospital discharge care plan",
        "dental treatment invoice",
        "occupational health assessment",
    ),
    "travel": (
        "international flight itinerary",
        "hotel accommodation confirmation",
        "travel insurance coverage certificate",
        "border entry and exit history",
        "vehicle ferry booking record",
        "rail season ticket renewal",
    ),
    "education": (
        "university degree transcript",
        "professional training completion award",
        "school enrollment confirmation",
        "language proficiency examination score",
        "research ethics approval notice",
        "student tuition payment receipt",
    ),
    "insurance": (
        "motor vehicle insurance policy",
        "home contents coverage schedule",
        "professional indemnity certificate",
        "life assurance beneficiary statement",
        "medical claim settlement letter",
        "commercial property risk assessment",
    ),
    "tax": (
        "personal income tax return",
        "value added tax registration",
        "corporate tax computation",
        "capital gains disposal statement",
        "payroll withholding reconciliation",
        "customs duty payment notice",
    ),
    "legal": (
        "court judgment enforcement order",
        "notarized power of attorney",
        "shareholder voting resolution",
        "data processing consent agreement",
        "trademark registration certificate",
        "civil dispute settlement terms",
    ),
    "property": (
        "registered land title extract",
        "commercial lease renewal",
        "building safety inspection report",
        "property purchase completion statement",
        "planning permission decision",
        "inventory and condition schedule",
    ),
    "operations": (
        "warehouse temperature incident log",
        "supplier quality audit finding",
        "equipment preventive maintenance record",
        "cybersecurity access review",
        "product recall traceability report",
        "business continuity exercise result",
    ),
}

FILLER_PARAGRAPHS = (
    "The administrative file was reviewed during the ordinary quarterly cycle. "
    "Routine correspondence, reference numbering, and retention notes are recorded for completeness.",
    "Staff followed the standard intake checklist and logged the date of receipt. "
    "This contextual material does not establish any separate entitlement or factual conclusion.",
    "The archive copy includes generic processing notes and a neutral chronology. "
    "Identifiers used in this paragraph are internal sequence markers without substantive meaning.",
    "Quality control confirmed that the pages were legible and arranged in the expected order. "
    "No exceptional handling instruction was attached to this background section.",
)


def concepts() -> list[tuple[str, str]]:
    return [(domain, concept) for domain, values in DOMAINS.items() for concept in values]


def filler(length: int, salt: str) -> str:
    rng = random.Random(int(hashlib.sha256(f"{SEED}:{salt}".encode()).hexdigest()[:16], 16))
    paragraphs: list[str] = []
    while len("\n\n".join(paragraphs)) < length:
        base = rng.choice(FILLER_PARAGRAPHS)
        paragraphs.append(f"{base} Batch reference {rng.randrange(100000, 999999)}.")
    return "\n\n".join(paragraphs)[:length].rstrip()


def target_section(concept: str, document_number: int, tag: str) -> str:
    marker = f"Evidence marker NEVIS-{document_number:03d}."
    sentence = (
        f"{marker} This record specifically establishes {concept}. "
        "The evidence was verified against the named person and the effective reporting period."
    )
    if tag == "oversized_paragraph":
        return sentence + " " + "continuous contextual token " * 360
    if tag == "token_fallback_sentence":
        return sentence.rstrip(".") + " " + "unbroken contextual token " * 360 + "."
    if tag == "multiple_relevant_chunks":
        return "\n\n".join((sentence, filler(1900, f"multi-{document_number}"), sentence, filler(1900, f"multi-b-{document_number}"), sentence))
    if tag == "multi_topic":
        return sentence + " The same file also contains generic catering, travel, and equipment administration notes."
    return sentence


def build_content(concept: str, number: int, tag: str, target_length: int) -> str:
    target = target_section(concept, number, tag)
    remaining = max(0, target_length - len(target) - 4)
    left = filler(remaining // 2, f"left-{number}")
    right = filler(remaining - len(left), f"right-{number}")
    if tag == "start":
        value = target + "\n\n" + filler(remaining, f"start-{number}")
    elif tag == "end":
        value = filler(remaining, f"end-{number}") + "\n\n" + target
    elif tag == "before_boundary":
        value = left + " " + target + "\n\n" + right
    elif tag == "after_boundary":
        value = left + "\n\n" + target + " " + right
    else:
        value = left + "\n\n" + target + "\n\n" + right
    if len(value) < target_length:
        value += "\n\n" + filler(target_length - len(value), f"tail-{number}")
    return value[:target_length]


def generate() -> dict[str, object]:
    all_concepts = concepts()
    documents: list[dict[str, object]] = []
    for index, (domain, concept) in enumerate(all_concepts):
        number = index + 1
        tag = PLACEMENT_TAGS[index % len(PLACEMENT_TAGS)]
        if index < 60:
            size_class = "standard-8-12kb"
            target_length = 8_400 + (index % 6) * 520
        elif index < 70:
            size_class = "large-20-40kb"
            target_length = 21_000 + (index - 60) * 1_950
        else:
            size_class = "stress-near-50000"
            target_length = 49_200 + (index - 70) * 600
        title = f"{domain.title()} evidence record {number:03d}"
        tags = [tag, domain]
        if tag == "misleading_title":
            title = f"Unrelated catering inventory {number:03d}"
        documents.append({
            "id": f"long-doc-{number:03d}",
            "title": title,
            "content": build_content(concept, number, tag, target_length),
            "tags": tags,
            "sizeClass": size_class,
            "concept": concept,
        })

    positive_queries: list[dict[str, object]] = []
    positive_categories = ("EXACT_LEXICAL", "MORPHOLOGICAL", "DOMAIN_VOCABULARY", "NATURAL_LANGUAGE", "AMBIGUOUS")
    for index, (_, concept) in enumerate(all_concepts):
        document = documents[index]
        marker = f"NEVIS-{index + 1:03d}" if index % 5 == 0 else concept
        text = marker if index % 5 == 0 else f"Which file proves {concept}?"
        judgments = {str(document["id"]): 3}
        if document["tags"][0] == "duplicate_near_duplicate" and index > 0:
            judgments[str(documents[index - 1]["id"])] = 2
        positive_queries.append({
            "id": f"long-positive-{index + 1:03d}",
            "text": text,
            "category": positive_categories[index % len(positive_categories)],
            "judgments": judgments,
            "tags": list(document["tags"]),
        })
    for index in range(18):
        concept = all_concepts[index][1]
        positive_queries.append({
            "id": f"long-positive-secondary-{index + 1:03d}",
            "text": f"Find the verified material concerning {concept.replace('record', 'documentation')}",
            "category": "NATURAL_LANGUAGE",
            "judgments": {str(documents[index]["id"]): 3},
            "tags": ["secondary_paraphrase", *documents[index]["tags"]],
        })

    negative_phrases = (
        "lunar mineral extraction permit", "deep sea coral census", "volcanic ash telescope calibration",
        "fictional dragon veterinary licence", "antarctic vineyard harvest", "quantum orchestra rehearsal schedule",
        "residential electricity outage complaint", "passport application appointment", "bank transfer cancellation request",
        "employment vacancy advertisement", "travel refund complaint", "school cafeteria weekly menu",
        "insurance marketing brochure", "tax office opening hours", "legal textbook borrowing record",
        "property valuation advertisement", "warehouse staff birthday rota", "medical clinic parking permit",
        "expired utility account for a different city", "unsigned draft tenancy template", "sample passport photograph guide",
        "hypothetical bank statement tutorial", "temporary volunteer role enquiry", "cancelled hotel wish list",
        "practice examination answer sheet", "rejected insurance quotation", "unsubmitted tax calculation example",
        "legal consultation marketing leaflet", "property viewing request", "warehouse temperature sensor catalogue",
    )
    negative_queries: list[dict[str, object]] = []
    negative_categories = ("EASY_NEGATIVE", "DOMAIN_NEGATIVE", "HARD_NEGATIVE")
    for index, phrase in enumerate(negative_phrases):
        negative_queries.append({
            "id": f"long-negative-{index + 1:03d}",
            "text": phrase,
            "category": negative_categories[index // 10],
            "judgments": {},
            "tags": ["negative", negative_categories[index // 10].lower()],
        })

    queries = positive_queries + negative_queries
    for index, query in enumerate(positive_queries):
        query["split"] = "TUNING" if index < 63 else "HOLDOUT"
    for index, query in enumerate(negative_queries):
        query["split"] = "TUNING" if index < 21 else "HOLDOUT"

    return {
        "metadata": {
            "name": "nevis-long-document-v1",
            "seed": SEED,
            "language": "en",
            "documentCount": 72,
            "queryCount": 120,
            "tuningQueryCount": 84,
            "holdoutQueryCount": 36,
        },
        "documents": documents,
        "queries": queries,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the deterministic Nevis long-document corpus")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "data" / "long-document-dataset.json")
    args = parser.parse_args()
    payload = generate()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(payload['documents'])} documents and {len(payload['queries'])} queries to {args.output}")


if __name__ == "__main__":
    main()
