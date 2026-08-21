from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import time
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from statistics import mean
from typing import Any, Iterable, Mapping, Sequence
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from dataset import EvaluationDataset, EvaluationQuery, load_dataset
from metrics import MetricsSummary, QueryRanking, RankedDocument, summarize


ANN_GRID = (
    (100, 250), (100, 500), (100, 750),
    (250, 250), (250, 500), (250, 750),
    (500, 500), (500, 750),
)
COMMON_PARAMETERS: dict[str, float | int] = {
    "documentCandidateLimit": 50,
    "chunkCandidateLimit": 250,
    "hnswEfSearch": 500,
    "rrfK": 60,
    "lexicalWeight": 1.25,
    "vectorWeight": 1.0,
}
BOUNDARY_TAGS = (
    "start", "middle", "end", "before_boundary", "after_boundary", "oversized_paragraph",
    "token_fallback_sentence", "misleading_title", "multi_topic", "duplicate_near_duplicate",
    "multiple_relevant_chunks",
)


@dataclass(frozen=True)
class ThresholdOutcome:
    threshold: float
    metrics: MetricsSummary


@dataclass(frozen=True)
class AnnFidelity:
    chunk_recall: float
    document_recall_at_50: float
    top_10_overlap: float
    distinct_documents: float
    maximum_chunks_per_document: float
    concentration: float
    p50_ms: float
    p95_ms: float


class ApiClient:
    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def get(self, path: str) -> dict[str, Any]:
        return self._request(path, None)

    def post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(path, payload)

    def _request(self, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        request = Request(
            f"{self.base_url}{path}",
            data=None if payload is None else json.dumps(payload).encode("utf-8"),
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            method="GET" if payload is None else "POST",
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{path} returned HTTP {error.code}: {body}") from error
        except URLError as error:
            raise RuntimeError(f"cannot reach {self.base_url}{path}: {error.reason}") from error


def create_corpus(client: ApiClient, dataset: EvaluationDataset) -> tuple[dict[str, str], float]:
    metadata = client.get("/internal/evaluation/metadata")
    if metadata["database"]["documentCount"] != 0 or metadata["database"]["chunkCount"] != 0:
        raise RuntimeError("quality runner requires a fresh, empty evaluation database")
    started = time.perf_counter()
    runtime_ids: dict[str, str] = {}
    for index, document in enumerate(dataset.documents, start=1):
        client_record = client.post("/clients", {
            "first_name": "LongCorpus",
            "last_name": f"Document {index}",
            "email": f"long-corpus-{index}@evaluation.invalid",
            "countryOfResidence": "UK",
        })
        document_record = client.post(f"/clients/{client_record['id']}/documents", {
            "title": document.title,
            "content": document.content,
        })
        runtime_ids[document.id] = document_record["id"]
    return runtime_ids, time.perf_counter() - started


def request_search(
        client: ApiClient,
        query: EvaluationQuery,
        retrieval: str,
        threshold: float,
        chunk_limit: int = 250,
        ef_search: int = 500,
        mode: str = "HYBRID",
) -> dict[str, Any]:
    return client.post("/internal/evaluation/search", {
        "query": query.text,
        "mode": mode,
        "semanticRetrieval": retrieval,
        **COMMON_PARAMETERS,
        "chunkCandidateLimit": chunk_limit,
        "hnswEfSearch": ef_search,
        "minimumSimilarity": threshold,
    })


def ranking_from_response(
        query: EvaluationQuery,
        response: Mapping[str, Any],
        runtime_aliases: Mapping[str, str],
        branch: str = "final",
) -> QueryRanking:
    score_key = {"final": "rrfScore", "semantic": "similarity", "lexical": "score"}[branch]
    results = tuple(
        RankedDocument(runtime_aliases[item["documentId"]], item["rank"], item[score_key])
        for item in response[branch]
    )
    return QueryRanking(query, results, int(response["timings"]["totalMs"]))


def run_threshold(
        client: ApiClient,
        queries: Sequence[EvaluationQuery],
        aliases: Mapping[str, str],
        threshold: float,
) -> tuple[ThresholdOutcome, list[dict[str, Any]]]:
    responses = [request_search(client, query, "EXACT", threshold) for query in queries]
    rankings = [ranking_from_response(query, response, aliases) for query, response in zip(queries, responses, strict=True)]
    return ThresholdOutcome(threshold, summarize(rankings)), responses


def quantile_threshold_candidates(scores: Iterable[float], maximum: int = 21) -> tuple[float, ...]:
    ordered = sorted(float(score) for score in scores)
    if not ordered:
        return (-1.0,)
    values = {-1.0, 0.30}
    count = min(maximum - len(values), len(ordered))
    for index in range(count):
        position = index * (len(ordered) - 1) / max(count - 1, 1)
        lower = int(position)
        upper = min(lower + 1, len(ordered) - 1)
        fraction = position - lower
        values.add(round(ordered[lower] + (ordered[upper] - ordered[lower]) * fraction, 6))
    return tuple(sorted(values))


def passes_threshold_gate(baseline: MetricsSummary, candidate: MetricsSummary) -> bool:
    return (
        candidate.recall_at_10 >= baseline.recall_at_10 - 0.01
        and candidate.mrr >= baseline.mrr - 0.02
        and candidate.ndcg_at_10 >= baseline.ndcg_at_10 - 0.02
        and candidate.precision_at_10 >= baseline.precision_at_10 - 0.02
    )


def select_threshold_candidate(outcomes: Sequence[ThresholdOutcome]) -> ThresholdOutcome:
    baseline = next(outcome for outcome in outcomes if outcome.threshold == -1.0)
    eligible = [outcome for outcome in outcomes if passes_threshold_gate(baseline.metrics, outcome.metrics)]
    return min(
        eligible,
        key=lambda outcome: (
            outcome.metrics.hard_negative_false_positive_rate,
            outcome.metrics.negative_false_positive_rate,
            -outcome.metrics.precision_at_10,
            -outcome.threshold,
        ),
    )


def ann_fidelity(
        exact_responses: Sequence[Mapping[str, Any]],
        approximate_responses: Sequence[Mapping[str, Any]],
) -> AnnFidelity:
    chunk_recalls: list[float] = []
    document_recalls: list[float] = []
    overlaps: list[float] = []
    latencies: list[float] = []
    distinct: list[float] = []
    maximum_chunks: list[float] = []
    concentrations: list[float] = []
    for exact, approximate in zip(exact_responses, approximate_responses, strict=True):
        exact_chunks = {(item["documentId"], item["chunkIndex"]) for item in exact["chunks"]}
        approximate_chunks = {(item["documentId"], item["chunkIndex"]) for item in approximate["chunks"]}
        chunk_recalls.append(len(exact_chunks & approximate_chunks) / len(exact_chunks) if exact_chunks else 1.0)
        exact_documents = {item["documentId"] for item in exact["semantic"][:50]}
        approximate_documents = {item["documentId"] for item in approximate["semantic"][:50]}
        document_recalls.append(len(exact_documents & approximate_documents) / len(exact_documents) if exact_documents else 1.0)
        exact_top_10 = {item["documentId"] for item in exact["semantic"][:10]}
        approximate_top_10 = {item["documentId"] for item in approximate["semantic"][:10]}
        overlaps.append(len(exact_top_10 & approximate_top_10) / len(exact_top_10) if exact_top_10 else 1.0)
        latencies.append(float(approximate["timings"]["semanticMs"]))
        diagnostics = approximate["diagnostics"]
        distinct.append(float(diagnostics["distinctDocuments"]))
        maximum_chunks.append(float(diagnostics["maximumChunksPerDocument"]))
        concentrations.append(float(diagnostics["concentration"]))
    return AnnFidelity(
        chunk_recall=mean(chunk_recalls),
        document_recall_at_50=mean(document_recalls),
        top_10_overlap=mean(overlaps),
        distinct_documents=mean(distinct),
        maximum_chunks_per_document=mean(maximum_chunks),
        concentration=mean(concentrations),
        p50_ms=percentile(latencies, 0.50),
        p95_ms=percentile(latencies, 0.95),
    )


def percentile(values: Sequence[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def run_ann_grid(
        client: ApiClient,
        queries: Sequence[EvaluationQuery],
        threshold: float,
        raw_dir: Path,
) -> list[dict[str, Any]]:
    exact_by_limit: dict[int, list[dict[str, Any]]] = {}
    for chunk_limit in sorted({chunk_limit for chunk_limit, _ in ANN_GRID}):
        exact_by_limit[chunk_limit] = [
            request_search(client, query, "EXACT", threshold, chunk_limit, max(chunk_limit, 500), "SEMANTIC")
            for query in queries
        ]
    summaries: list[dict[str, Any]] = []
    for chunk_limit, ef_search in ANN_GRID:
        approximate = [
            request_search(client, query, "HNSW", threshold, chunk_limit, ef_search, "SEMANTIC")
            for query in queries
        ]
        fidelity = ann_fidelity(exact_by_limit[chunk_limit], approximate)
        summaries.append({"chunkCandidateLimit": chunk_limit, "hnswEfSearch": ef_search, **asdict(fidelity)})
        (raw_dir / f"ann-{chunk_limit}-{ef_search}.json").write_text(
            json.dumps(approximate, indent=2), encoding="utf-8"
        )
    for chunk_limit, responses in exact_by_limit.items():
        (raw_dir / f"exact-{chunk_limit}.json").write_text(json.dumps(responses, indent=2), encoding="utf-8")
    return summaries


def case_metrics(
        queries: Sequence[EvaluationQuery],
        responses: Sequence[Mapping[str, Any]],
        aliases: Mapping[str, str],
) -> tuple[dict[str, dict[str, float | int]], list[dict[str, Any]]]:
    rankings = [
        ranking_from_response(query, response, aliases)
        for query, response in zip(queries, responses, strict=True)
    ]
    per_tag: dict[str, dict[str, float | int]] = {}
    for tag in BOUNDARY_TAGS:
        tagged = [ranking for ranking in rankings if tag in ranking.query.tags and ranking.query.has_relevant_document]
        if tagged:
            metrics = summarize(tagged)
            per_tag[tag] = {
                "queryCount": len(tagged),
                "recallAt10": metrics.recall_at_10,
                "mrr": metrics.mrr,
                "ndcgAt10": metrics.ndcg_at_10,
            }
    failures = []
    for ranking in rankings:
        if not ranking.query.has_relevant_document:
            continue
        relevant = {document_id for document_id, grade in ranking.query.judgments.items() if grade >= 2}
        retrieved = {result.document_id for result in ranking.results[:10]}
        if not relevant.issubset(retrieved):
            failures.append({
                "queryId": ranking.query.id,
                "tags": list(ranking.query.tags),
                "missingRelevantDocuments": sorted(relevant - retrieved),
                "top10": [result.document_id for result in ranking.results[:10]],
            })
    return per_tag, failures


def dataset_checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args: argparse.Namespace) -> None:
    output_root = (Path(__file__).parent / "output").resolve()
    output = args.output.resolve()
    if not output.is_relative_to(output_root) or output == output_root:
        raise ValueError(f"output must be a run directory below {output_root}")
    if output.exists():
        shutil.rmtree(output)
    raw_dir = output / "raw"
    raw_dir.mkdir(parents=True)

    dataset = load_dataset(args.dataset)
    queries = dataset.queries_for(args.split.upper())
    client = ApiClient(args.base_url, args.timeout)
    startup_metadata = client.get("/internal/evaluation/metadata")
    runtime_ids, indexing_seconds = create_corpus(client, dataset)
    aliases = {runtime_id: alias for alias, runtime_id in runtime_ids.items()}
    populated_metadata = client.get("/internal/evaluation/metadata")

    if args.fixed_threshold is None:
        permissive, permissive_responses = run_threshold(client, queries, aliases, -1.0)
        scores = (
            item["similarity"]
            for response in permissive_responses
            for item in response["semantic"]
        )
        thresholds = quantile_threshold_candidates(scores)
        outcomes = [permissive]
        raw_thresholds: dict[str, Any] = {"-1.000000": permissive_responses}
        for threshold in thresholds:
            if threshold == -1.0:
                continue
            outcome, responses = run_threshold(client, queries, aliases, threshold)
            outcomes.append(outcome)
            raw_thresholds[f"{threshold:.6f}"] = responses
        selected = select_threshold_candidate(outcomes)
        selection_mode = "quantile-tuning"
    else:
        selected, selected_responses = run_threshold(client, queries, aliases, args.fixed_threshold)
        outcomes = [selected]
        raw_thresholds = {f"{args.fixed_threshold:.6f}": selected_responses}
        selection_mode = "fixed-holdout"

    selected_responses = raw_thresholds[f"{selected.threshold:.6f}"]
    per_case_metrics, failures = case_metrics(queries, selected_responses, aliases)

    ann = run_ann_grid(client, queries, -1.0, raw_dir) if args.ann_grid else []
    plan_exact = client.post("/internal/evaluation/semantic-plan", {
        "query": queries[0].text,
        "semanticRetrieval": "EXACT",
        "chunkCandidateLimit": 250,
        "hnswEfSearch": 500,
    })
    plan_hnsw = client.post("/internal/evaluation/semantic-plan", {
        "query": queries[0].text,
        "semanticRetrieval": "HNSW",
        "chunkCandidateLimit": 250,
        "hnswEfSearch": 500,
    })

    (output / "runtime-document-ids.json").write_text(json.dumps(runtime_ids, indent=2), encoding="utf-8")
    (raw_dir / "threshold-responses.json").write_text(json.dumps(raw_thresholds, indent=2), encoding="utf-8")
    (raw_dir / "representative-plans.json").write_text(
        json.dumps({"exact": plan_exact, "hnsw": plan_hnsw}, indent=2), encoding="utf-8"
    )
    aggregate = {
        "runAt": datetime.now(UTC).isoformat(),
        "dataset": str(args.dataset),
        "datasetSha256": dataset_checksum(args.dataset),
        "split": args.split.upper(),
        "startupMetadata": startup_metadata,
        "populatedMetadata": populated_metadata,
        "indexingSeconds": indexing_seconds,
        "indexingDocumentsPerSecond": len(dataset.documents) / indexing_seconds,
        "indexingChunksPerSecond": populated_metadata["database"]["chunkCount"] / indexing_seconds,
        "thresholdCandidates": [outcome.threshold for outcome in outcomes],
        "thresholdSelection": selection_mode,
        "thresholdOutcomes": [
            {"threshold": outcome.threshold, **outcome.metrics.json()} for outcome in outcomes
        ],
        "selectedThreshold": selected.threshold,
        "selectedMetrics": selected.metrics.json(),
        "caseMetrics": per_case_metrics,
        "positiveTop10Failures": failures,
        "annGrid": ann,
        "warning": (
            "document Recall@50 below 0.98"
            if any(item["document_recall_at_50"] < 0.98 for item in ann)
            else None
        ),
    }
    (output / "aggregate.json").write_text(json.dumps(aggregate, indent=2), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "selectedThreshold": selected.threshold,
        "metrics": selected.metrics.json(),
        "annBaseline": next((item for item in ann if item["chunkCandidateLimit"] == 250 and item["hnswEfSearch"] == 500), None),
    }, indent=2))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run chunked long-document quality and ANN evaluation")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--dataset", type=Path, default=Path(__file__).parent / "data" / "long-document-dataset.json")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "output" / "quality-latest")
    parser.add_argument("--split", choices=("tuning", "holdout"), default="tuning")
    parser.add_argument("--ann-grid", action="store_true")
    parser.add_argument("--fixed-threshold", type=float)
    parser.add_argument("--timeout", type=float, default=180.0)
    return parser.parse_args()


if __name__ == "__main__":
    try:
        run(parse_args())
    except Exception as error:
        print(f"Quality evaluation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
