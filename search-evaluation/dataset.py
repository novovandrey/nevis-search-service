from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


VALID_CATEGORIES = {
    "EXACT_LEXICAL",
    "MORPHOLOGICAL",
    "DOMAIN_VOCABULARY",
    "NATURAL_LANGUAGE",
    "AMBIGUOUS",
    "EASY_NEGATIVE",
    "DOMAIN_NEGATIVE",
    "HARD_NEGATIVE",
}
NEGATIVE_CATEGORIES = {"EASY_NEGATIVE", "DOMAIN_NEGATIVE", "HARD_NEGATIVE"}
VALID_SPLITS = {"TUNING", "HOLDOUT"}


@dataclass(frozen=True)
class EvaluationDocument:
    id: str
    title: str
    content: str
    tags: tuple[str, ...] = ()
    size_class: str | None = None
    concept: str | None = None


@dataclass(frozen=True)
class EvaluationQuery:
    id: str
    text: str
    category: str
    split: str
    judgments: Mapping[str, int]
    tags: tuple[str, ...] = ()

    def grade_for(self, document_id: str) -> int:
        return self.judgments.get(document_id, 0)

    @property
    def has_relevant_document(self) -> bool:
        return any(grade >= 2 for grade in self.judgments.values())

    @property
    def is_negative(self) -> bool:
        return self.category in NEGATIVE_CATEGORIES


@dataclass(frozen=True)
class EvaluationDataset:
    documents: tuple[EvaluationDocument, ...]
    queries: tuple[EvaluationQuery, ...]

    def queries_for(self, split: str) -> tuple[EvaluationQuery, ...]:
        return tuple(query for query in self.queries if query.split == split)


def load_dataset(path: Path) -> EvaluationDataset:
    raw = json.loads(path.read_text(encoding="utf-8"))
    documents = tuple(
        EvaluationDocument(
            id=document["id"],
            title=document["title"],
            content=document["content"],
            tags=tuple(document.get("tags", ())),
            size_class=document.get("sizeClass"),
            concept=document.get("concept"),
        )
        for document in raw["documents"]
    )
    queries = tuple(
        EvaluationQuery(
            id=query["id"],
            text=query["text"],
            category=query["category"],
            split=query["split"],
            judgments=query["judgments"],
            tags=tuple(query.get("tags", ())),
        )
        for query in raw["queries"]
    )
    dataset = EvaluationDataset(documents, queries)
    validate(dataset)
    return dataset


def validate(dataset: EvaluationDataset) -> None:
    if not dataset.documents or not dataset.queries:
        raise ValueError("dataset must contain documents and queries")
    document_ids = {document.id for document in dataset.documents}
    if len(document_ids) != len(dataset.documents):
        raise ValueError("document ids must be unique")
    query_ids = {query.id for query in dataset.queries}
    if len(query_ids) != len(dataset.queries):
        raise ValueError("query ids must be unique")
    if not dataset.queries_for("TUNING") or not dataset.queries_for("HOLDOUT"):
        raise ValueError("dataset must contain tuning and holdout queries")
    for document in dataset.documents:
        if not document.id or not document.title.strip() or not document.content.strip():
            raise ValueError(f"document {document.id!r} must have an id, title and content")
        if len(set(document.tags)) != len(document.tags):
            raise ValueError(f"document {document.id} has duplicate tags")
    for query in dataset.queries:
        if not query.id or not query.text.strip():
            raise ValueError("query must have an id and text")
        if len(set(query.tags)) != len(query.tags):
            raise ValueError(f"query {query.id} has duplicate tags")
        if query.category not in VALID_CATEGORIES:
            raise ValueError(f"unknown query category: {query.category}")
        if query.split not in VALID_SPLITS:
            raise ValueError(f"unknown dataset split: {query.split}")
        if any(document_id not in document_ids for document_id in query.judgments):
            raise ValueError(f"query {query.id} has a judgment for an unknown document")
        if any(not isinstance(grade, int) or grade < 0 or grade > 3 for grade in query.judgments.values()):
            raise ValueError(f"query {query.id} has a relevance grade outside [0, 3]")
        if query.is_negative and query.has_relevant_document:
            raise ValueError(f"negative query {query.id} must not have relevant documents")
        if not query.is_negative and not query.has_relevant_document:
            raise ValueError(f"positive query {query.id} needs a grade 2 or 3 judgment")
