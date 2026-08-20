from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from html import escape
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from dataset import EvaluationDataset, EvaluationQuery, load_dataset
from metrics import MetricsSummary, QueryRanking, RankedDocument, summarize


@dataclass(frozen=True)
class ExperimentConfig:
    name: str
    mode: str
    overrides: dict[str, float | int]


@dataclass(frozen=True)
class ExperimentOutcome:
    config: ExperimentConfig
    effective_parameters: dict[str, float | int]
    metrics: MetricsSummary
    rankings: tuple[QueryRanking, ...]
    raw_responses: tuple[dict[str, Any], ...]


class ApiClient:
    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{path} returned HTTP {error.code}: {body}") from error
        except URLError as error:
            raise RuntimeError(f"cannot reach {self.base_url}{path}: {error.reason}") from error


def create_corpus(client: ApiClient, dataset: EvaluationDataset) -> dict[str, str]:
    """Create the deterministic synthetic corpus through the ordinary public API."""
    runtime_ids: dict[str, str] = {}
    for index, document in enumerate(dataset.documents, start=1):
        created_client = client.post(
            "/clients",
            {
                "first_name": "Benchmark",
                "last_name": f"Document {index}",
                "email": f"benchmark-document-{index}@evaluation.invalid",
                "countryOfResidence": "UK",
            },
        )
        created_document = client.post(
            f"/clients/{created_client['id']}/documents",
            {"title": document.title, "content": document.content},
        )
        runtime_ids[document.id] = created_document["id"]
    return runtime_ids


def run_experiment(
    client: ApiClient,
    queries: Iterable[EvaluationQuery],
    runtime_ids: dict[str, str],
    config: ExperimentConfig,
) -> ExperimentOutcome:
    aliases_by_runtime_id = {runtime_id: alias for alias, runtime_id in runtime_ids.items()}
    rankings: list[QueryRanking] = []
    raw_responses: list[dict[str, Any]] = []
    effective_parameters: dict[str, float | int] | None = None
    for query in queries:
        response = client.post(
            "/internal/evaluation/search",
            {"query": query.text, "mode": config.mode, **config.overrides},
        )
        response_parameters = response["parameters"]
        if effective_parameters is None:
            effective_parameters = response_parameters
        elif response_parameters != effective_parameters:
            raise RuntimeError(f"effective parameters changed during {config.name}")
        rankings.append(
            QueryRanking(
                query=query,
                results=tuple(_selected_results(response, config.mode, aliases_by_runtime_id)),
                total_ms=response["timings"]["totalMs"],
            )
        )
        raw_responses.append({"queryId": query.id, "request": {"query": query.text, "mode": config.mode, **config.overrides}, "response": response})
    if effective_parameters is None:
        raise ValueError(f"experiment {config.name} has no queries")
    return ExperimentOutcome(
        config=config,
        effective_parameters=effective_parameters,
        metrics=summarize(rankings),
        rankings=tuple(rankings),
        raw_responses=tuple(raw_responses),
    )


def _selected_results(response: dict[str, Any], mode: str, aliases_by_runtime_id: dict[str, str]) -> list[RankedDocument]:
    branch, score_key = {
        "LEXICAL": ("lexical", "score"),
        "SEMANTIC": ("semantic", "similarity"),
        "HYBRID": ("final", "rrfScore"),
    }[mode]
    results: list[RankedDocument] = []
    for result in response[branch]:
        try:
            document_id = aliases_by_runtime_id[result["documentId"]]
        except KeyError as error:
            raise RuntimeError(
                "evaluation response contains a document outside the benchmark corpus; "
                "use a dedicated empty evaluation database"
            ) from error
        results.append(RankedDocument(document_id, result["rank"], result[score_key]))
    return results


def write_outcome(output_dir: Path, outcome: ExperimentOutcome) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "raw").mkdir(exist_ok=True)
    (output_dir / "per-query").mkdir(exist_ok=True)
    (output_dir / "raw" / f"{outcome.config.name}.json").write_text(
        json.dumps(list(outcome.raw_responses), indent=2), encoding="utf-8"
    )
    (output_dir / "per-query" / f"{outcome.config.name}.json").write_text(
        json.dumps([_ranking_json(ranking) for ranking in outcome.rankings], indent=2), encoding="utf-8"
    )


def write_summary_table(output_dir: Path, outcomes: Iterable[ExperimentOutcome]) -> None:
    rows = [_summary_row(outcome) for outcome in outcomes]
    if not rows:
        return
    path = output_dir / "metrics.csv"
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def _summary_row(outcome: ExperimentOutcome) -> dict[str, Any]:
    return {
        "experiment": outcome.config.name,
        "mode": outcome.config.mode,
        **outcome.effective_parameters,
        **outcome.metrics.json(),
    }


def _ranking_json(ranking: QueryRanking) -> dict[str, Any]:
    return {
        "query": ranking.query.id,
        "text": ranking.query.text,
        "category": ranking.query.category,
        "split": ranking.query.split,
        "judgments": dict(ranking.query.judgments),
        "results": [asdict(result) for result in ranking.results],
        "totalMs": ranking.total_ms,
    }


def choose_quality_preserving(
    baseline: ExperimentOutcome,
    candidates: Iterable[ExperimentOutcome],
    smallest_parameter: str | None = None,
    preferred_parameter: str | None = None,
) -> ExperimentOutcome:
    """Keep Recall@10 stable, then reduce noise before preferring small/simple settings."""
    supported = [
        candidate
        for candidate in candidates
        if candidate.metrics.recall_at_10 >= baseline.metrics.recall_at_10 - 0.01
    ]
    if not supported:
        supported = list(candidates)
    if not supported:
        raise ValueError("at least one candidate is required")

    def quality_key(candidate: ExperimentOutcome) -> tuple[float, float, float, float, float]:
        smallest_value = candidate.effective_parameters.get(smallest_parameter, 0) if smallest_parameter else 0
        preferred_value = candidate.effective_parameters.get(preferred_parameter, 0) if preferred_parameter else 0
        baseline_value = baseline.effective_parameters.get(preferred_parameter, 0) if preferred_parameter else 0
        return (
            candidate.metrics.precision_at_10,
            -candidate.metrics.negative_false_positive_rate,
            candidate.metrics.ndcg_at_10,
            candidate.metrics.mrr,
            -float(smallest_value) if smallest_parameter else -abs(float(preferred_value) - float(baseline_value)),
        )

    return max(supported, key=quality_key)


def write_line_chart(path: Path, outcomes: Iterable[ExperimentOutcome], parameter: str, title: str) -> None:
    outcomes = list(outcomes)
    if not outcomes:
        return
    width, height, padding = 820, 420, 60
    values = [float(outcome.effective_parameters[parameter]) for outcome in outcomes]
    x_min, x_max = min(values), max(values)
    x_span = x_max - x_min or 1
    metrics = {
        "Recall@10": [outcome.metrics.recall_at_10 for outcome in outcomes],
        "MRR": [outcome.metrics.mrr for outcome in outcomes],
        "NDCG@10": [outcome.metrics.ndcg_at_10 for outcome in outcomes],
        "Precision@10": [outcome.metrics.precision_at_10 for outcome in outcomes],
    }
    colors = ["#1565c0", "#2e7d32", "#8e24aa", "#ef6c00"]
    x = lambda value: padding + (value - x_min) / x_span * (width - 2 * padding)
    y = lambda value: height - padding - value * (height - 2 * padding)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        f'<text x="{padding}" y="28" font-family="sans-serif" font-size="18">{escape(title)}</text>',
        f'<line x1="{padding}" y1="{height - padding}" x2="{width - padding}" y2="{height - padding}" stroke="#555"/>',
        f'<line x1="{padding}" y1="{padding}" x2="{padding}" y2="{height - padding}" stroke="#555"/>',
    ]
    for tick in range(6):
        value = tick / 5
        tick_y = y(value)
        parts.append(f'<line x1="{padding}" y1="{tick_y:.1f}" x2="{width - padding}" y2="{tick_y:.1f}" stroke="#ddd"/>')
        parts.append(f'<text x="12" y="{tick_y + 4:.1f}" font-family="sans-serif" font-size="12">{value:.1f}</text>')
    for value in values:
        parts.append(f'<text x="{x(value) - 10:.1f}" y="{height - 34}" font-family="sans-serif" font-size="12">{value:g}</text>')
    for color, (name, series) in zip(colors, metrics.items(), strict=True):
        points = " ".join(f"{x(value):.1f},{y(metric):.1f}" for value, metric in zip(values, series, strict=True))
        parts.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="2.5"/>')
        parts.append(f'<text x="{padding + colors.index(color) * 130}" y="{height - 10}" fill="{color}" font-family="sans-serif" font-size="13">{name}</text>')
    parts.append(f'<text x="{width / 2 - 70}" y="{height - 34}" font-family="sans-serif" font-size="12">{escape(parameter)}</text>')
    parts.append("</svg>")
    path.write_text("\n".join(parts), encoding="utf-8")


def write_similarity_chart(path: Path, outcome: ExperimentOutcome) -> None:
    points: list[tuple[float, int]] = []
    for ranking in outcome.rankings:
        for result in ranking.results:
            points.append((result.score, ranking.query.grade_for(result.document_id)))
    width, height, padding = 820, 310, 60
    x_values = [score for score, _ in points] or [0.0]
    lower, upper = min(x_values), max(x_values)
    span = upper - lower or 1.0
    x = lambda score: padding + (score - lower) / span * (width - 2 * padding)
    y = lambda grade: height - padding - grade / 3 * (height - 2 * padding)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        f'<text x="{padding}" y="28" font-family="sans-serif" font-size="18">Semantic similarity by relevance grade</text>',
        f'<line x1="{padding}" y1="{height - padding}" x2="{width - padding}" y2="{height - padding}" stroke="#555"/>',
        f'<line x1="{padding}" y1="{padding}" x2="{padding}" y2="{height - padding}" stroke="#555"/>',
    ]
    for grade in range(4):
        parts.append(f'<text x="30" y="{y(grade) + 4:.1f}" font-family="sans-serif" font-size="12">grade {grade}</text>')
        parts.append(f'<line x1="{padding}" y1="{y(grade):.1f}" x2="{width - padding}" y2="{y(grade):.1f}" stroke="#eee"/>')
    for score, grade in points:
        color = "#2e7d32" if grade >= 2 else "#c62828"
        parts.append(f'<circle cx="{x(score):.1f}" cy="{y(grade):.1f}" r="4" fill="{color}" fill-opacity="0.75"/>')
    parts.append(f'<text x="{padding}" y="{height - 12}" font-family="sans-serif" font-size="12">similarity range: {lower:.3f} to {upper:.3f}</text>')
    parts.append("</svg>")
    path.write_text("\n".join(parts), encoding="utf-8")


def run(args: argparse.Namespace) -> None:
    dataset = load_dataset(args.dataset)
    output_dir = args.output.resolve()
    output_root = (Path(__file__).parent / "output").resolve()
    if not output_dir.is_relative_to(output_root) or output_dir == output_root:
        raise ValueError(f"output must be a subdirectory of {output_root}")
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)
    client = ApiClient(args.base_url, args.timeout)
    runtime_ids = create_corpus(client, dataset)
    (output_dir / "runtime-document-ids.json").write_text(json.dumps(runtime_ids, indent=2), encoding="utf-8")
    tuning = dataset.queries_for("TUNING")
    holdout = dataset.queries_for("HOLDOUT")

    baseline = run_experiment(client, tuning, runtime_ids, ExperimentConfig("baseline-hybrid", "HYBRID", {}))
    mode_outcomes = [
        run_experiment(client, tuning, runtime_ids, ExperimentConfig(f"mode-{mode.lower()}", mode, {}))
        for mode in ("LEXICAL", "SEMANTIC", "HYBRID")
    ]
    semantic_distribution = run_experiment(
        client, tuning, runtime_ids,
        ExperimentConfig("semantic-distribution", "SEMANTIC", {"minimumSimilarity": -1.0, "candidateLimit": 200}),
    )
    threshold_outcomes = [
        run_experiment(client, tuning, runtime_ids, ExperimentConfig(
            f"threshold-{threshold:.2f}".replace("-", "minus-"), "HYBRID", {"minimumSimilarity": threshold}
        ))
        for threshold in (-1.0, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50)
    ]
    chosen_threshold = choose_quality_preserving(baseline, threshold_outcomes)
    candidate_outcomes = [
        run_experiment(client, tuning, runtime_ids, ExperimentConfig(
            f"candidate-limit-{limit}", "HYBRID", {
                "minimumSimilarity": chosen_threshold.effective_parameters["minimumSimilarity"], "candidateLimit": limit
            }
        ))
        for limit in (10, 20, 50, 100, 200)
    ]
    chosen_candidate = choose_quality_preserving(chosen_threshold, candidate_outcomes, "candidateLimit")
    rrf_outcomes = [
        run_experiment(client, tuning, runtime_ids, ExperimentConfig(
            f"rrf-k-{rrf_k}", "HYBRID", {
                "minimumSimilarity": chosen_candidate.effective_parameters["minimumSimilarity"],
                "candidateLimit": chosen_candidate.effective_parameters["candidateLimit"],
                "rrfK": rrf_k,
            }
        ))
        for rrf_k in (10, 20, 40, 60, 100)
    ]
    chosen_rrf = choose_quality_preserving(chosen_candidate, rrf_outcomes, preferred_parameter="rrfK")
    weight_outcomes = [
        run_experiment(client, tuning, runtime_ids, ExperimentConfig(
            f"weights-{lexical_weight:.2f}-{vector_weight:.2f}", "HYBRID", {
                "minimumSimilarity": chosen_rrf.effective_parameters["minimumSimilarity"],
                "candidateLimit": chosen_rrf.effective_parameters["candidateLimit"],
                "rrfK": chosen_rrf.effective_parameters["rrfK"],
                "lexicalWeight": lexical_weight,
                "vectorWeight": vector_weight,
            }
        ))
        for lexical_weight, vector_weight in ((1.0, 1.0), (1.25, 1.0), (1.0, 1.25))
    ]
    chosen_final = choose_quality_preserving(chosen_rrf, weight_outcomes)
    holdout_outcome = run_experiment(
        client, holdout, runtime_ids,
        ExperimentConfig("holdout-final", "HYBRID", chosen_final.effective_parameters),
    )

    all_outcomes = [baseline, *mode_outcomes, semantic_distribution, *threshold_outcomes, *candidate_outcomes,
                    *rrf_outcomes, *weight_outcomes, holdout_outcome]
    for outcome in all_outcomes:
        write_outcome(output_dir, outcome)
    write_summary_table(output_dir, all_outcomes)
    (output_dir / "recommendations.json").write_text(json.dumps({
        "selectionRule": "Recall@10 may fall by at most 0.01 versus the immediately preceding baseline; then maximize Precision@10 and negative-query behavior before NDCG@10 and MRR. Candidate limits prefer the smallest tied value; RRF ties prefer the existing value.",
        "baseline": _summary_row(baseline),
        "chosenThreshold": _summary_row(chosen_threshold),
        "chosenCandidateLimit": _summary_row(chosen_candidate),
        "chosenRrfK": _summary_row(chosen_rrf),
        "chosenFinalTuning": _summary_row(chosen_final),
        "holdout": _summary_row(holdout_outcome),
    }, indent=2), encoding="utf-8")
    charts = output_dir / "charts"
    charts.mkdir(exist_ok=True)
    write_similarity_chart(charts / "semantic-similarity.svg", semantic_distribution)
    write_line_chart(charts / "threshold-quality.svg", threshold_outcomes, "minimumSimilarity", "Threshold experiment")
    write_line_chart(charts / "candidate-limit-quality.svg", candidate_outcomes, "candidateLimit", "Candidate-limit experiment")
    write_line_chart(charts / "rrf-k-quality.svg", rrf_outcomes, "rrfK", "RRF k experiment")
    (output_dir / "run-metadata.json").write_text(json.dumps({
        "runAt": datetime.now(UTC).isoformat(), "baseUrl": args.base_url, "documentCount": len(dataset.documents),
        "tuningQueryCount": len(tuning), "holdoutQueryCount": len(holdout),
    }, indent=2), encoding="utf-8")
    print(f"Evaluation complete. Review {output_dir / 'metrics.csv'} and {output_dir / 'recommendations.json'}.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate Nevis search through the evaluation-profile HTTP API")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", type=Path, default=Path(__file__).parent / "data" / "dataset.json")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "output" / "latest")
    parser.add_argument("--timeout", type=float, default=30.0)
    return parser.parse_args()


if __name__ == "__main__":
    try:
        run(parse_args())
    except Exception as error:
        print(f"Evaluation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
