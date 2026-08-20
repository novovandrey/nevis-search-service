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
from statistics import quantiles
from typing import Any, Iterable, Mapping
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from dataset import EvaluationDataset, EvaluationQuery, load_dataset
from metrics import MetricsSummary, QueryRanking, RankedDocument, summarize


BASELINE_MINIMUM_SIMILARITY = 0.30
THRESHOLDS = (0.30, 0.35, 0.40, 0.45, 0.50)


@dataclass(frozen=True)
class ExperimentConfig:
    name: str
    mode: str
    overrides: dict[str, float | int]


@dataclass(frozen=True)
class QueryDiagnostics:
    lexical_document_ids: frozenset[str]
    semantic_scores: Mapping[str, float]
    top_semantic_scores: tuple[float, ...]
    top_semantic_gap: float | None
    lexical_candidate_count: int
    semantic_candidate_count: int
    final_candidate_count: int

    @property
    def agreement_document_ids(self) -> frozenset[str]:
        return frozenset(self.lexical_document_ids & self.semantic_scores.keys())


@dataclass(frozen=True)
class ExperimentOutcome:
    config: ExperimentConfig
    effective_parameters: dict[str, float | int]
    metrics: MetricsSummary
    rankings: tuple[QueryRanking, ...]
    diagnostics: tuple[QueryDiagnostics, ...]
    raw_responses: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class RejectionPolicy:
    name: str
    semantic_only_minimum_similarity: float = BASELINE_MINIMUM_SIMILARITY
    minimum_gap: float | None = None

    def parameters(self) -> dict[str, float | int]:
        values: dict[str, float | int] = {"semanticOnlyMinimumSimilarity": self.semantic_only_minimum_similarity}
        if self.minimum_gap is not None:
            values["minimumSemanticGap"] = self.minimum_gap
        return values


class ApiClient:
    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        request = Request(f"{self.base_url}{path}", data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{path} returned HTTP {error.code}: {body}") from error
        except URLError as error:
            raise RuntimeError(f"cannot reach {self.base_url}{path}: {error.reason}") from error


def create_corpus(client: ApiClient, dataset: EvaluationDataset) -> dict[str, str]:
    runtime_ids: dict[str, str] = {}
    for index, document in enumerate(dataset.documents, start=1):
        client_record = client.post("/clients", {"first_name": "Benchmark", "last_name": f"Document {index}", "email": f"benchmark-document-{index}@evaluation.invalid", "countryOfResidence": "UK"})
        document_record = client.post(f"/clients/{client_record['id']}/documents", {"title": document.title, "content": document.content})
        runtime_ids[document.id] = document_record["id"]
    return runtime_ids


def run_experiment(client: ApiClient, queries: Iterable[EvaluationQuery], runtime_ids: dict[str, str], config: ExperimentConfig) -> ExperimentOutcome:
    aliases = {runtime_id: alias for alias, runtime_id in runtime_ids.items()}
    rankings: list[QueryRanking] = []
    diagnostics: list[QueryDiagnostics] = []
    raw_responses: list[dict[str, Any]] = []
    effective_parameters: dict[str, float | int] | None = None
    for query in queries:
        request = {"query": query.text, "mode": config.mode, **config.overrides}
        response = client.post("/internal/evaluation/search", request)
        if effective_parameters is None:
            effective_parameters = response["parameters"]
        elif response["parameters"] != effective_parameters:
            raise RuntimeError(f"effective parameters changed during {config.name}")
        rankings.append(QueryRanking(query, tuple(_selected_results(response, config.mode, aliases)), response["timings"]["totalMs"]))
        diagnostics.append(_diagnostics(response, aliases))
        raw_responses.append({"queryId": query.id, "request": request, "response": response})
    if effective_parameters is None:
        raise ValueError(f"experiment {config.name} has no queries")
    return ExperimentOutcome(config, effective_parameters, summarize(rankings), tuple(rankings), tuple(diagnostics), tuple(raw_responses))


def _document_id(result: Mapping[str, Any], aliases: Mapping[str, str]) -> str:
    try:
        return aliases[result["documentId"]]
    except KeyError as error:
        raise RuntimeError("evaluation response contains a document outside the benchmark corpus; use an empty evaluation database") from error


def _selected_results(response: Mapping[str, Any], mode: str, aliases: Mapping[str, str]) -> list[RankedDocument]:
    branch, score_key = {"LEXICAL": ("lexical", "score"), "SEMANTIC": ("semantic", "similarity"), "HYBRID": ("final", "rrfScore")}[mode]
    return [RankedDocument(_document_id(result, aliases), result["rank"], result[score_key]) for result in response[branch]]


def _diagnostics(response: Mapping[str, Any], aliases: Mapping[str, str]) -> QueryDiagnostics:
    semantic = response["semantic"]
    scores = tuple(result["similarity"] for result in semantic[:3])
    return QueryDiagnostics(
        frozenset(_document_id(result, aliases) for result in response["lexical"]),
        {_document_id(result, aliases): result["similarity"] for result in semantic}, scores,
        scores[0] - scores[1] if len(scores) >= 2 else None,
        len(response["lexical"]), len(semantic), len(response["final"]),
    )


def apply_rejection_policy(baseline: ExperimentOutcome, policy: RejectionPolicy) -> ExperimentOutcome:
    """Post-filter Java's final ranking; Python never rebuilds retrieval or RRF."""
    rankings: list[QueryRanking] = []
    for ranking, diagnostic in zip(baseline.rankings, baseline.diagnostics, strict=True):
        accepted: list[RankedDocument] = []
        for result in ranking.results:
            lexical_supported = result.document_id in diagnostic.lexical_document_ids
            semantic_score = diagnostic.semantic_scores.get(result.document_id)
            semantic_only = semantic_score is not None and not lexical_supported
            keep = lexical_supported or (semantic_only and semantic_score >= policy.semantic_only_minimum_similarity and (policy.minimum_gap is None or (diagnostic.top_semantic_gap or 0.0) >= policy.minimum_gap))
            if keep:
                accepted.append(result)
        rankings.append(QueryRanking(ranking.query, tuple(RankedDocument(result.document_id, index, result.score) for index, result in enumerate(accepted, start=1)), ranking.total_ms))
    return ExperimentOutcome(ExperimentConfig(policy.name, "HYBRID", policy.parameters()), {**baseline.effective_parameters, **policy.parameters()}, summarize(rankings), tuple(rankings), baseline.diagnostics, baseline.raw_responses)


def observed_cutpoints(values: Iterable[float], lower_bound: float = 0.0) -> tuple[float, ...]:
    usable = sorted({round(value, 6) for value in values if value >= lower_bound})
    if len(usable) <= 4:
        return tuple(sorted({lower_bound, *usable}))
    return tuple(sorted({lower_bound, usable[0], *quantiles(usable, n=4, method="inclusive"), usable[-1]}))


def policy_candidates(baseline: ExperimentOutcome) -> list[RejectionPolicy]:
    scores = [item.top_semantic_scores[0] for item in baseline.diagnostics if item.top_semantic_scores]
    gaps = [item.top_semantic_gap for item in baseline.diagnostics if item.top_semantic_gap is not None]
    score_cutpoints, gap_cutpoints = observed_cutpoints(scores, BASELINE_MINIMUM_SIMILARITY), observed_cutpoints(gaps)
    return [
        RejectionPolicy("policy-baseline"),
        *(RejectionPolicy(f"policy-agreement-{score:.6f}", score) for score in score_cutpoints),
        *(RejectionPolicy(f"policy-gap-{gap:.6f}", BASELINE_MINIMUM_SIMILARITY, gap) for gap in gap_cutpoints),
        *(RejectionPolicy(f"policy-combined-{score:.6f}-{gap:.6f}", score, gap) for score in score_cutpoints for gap in gap_cutpoints),
    ]


def passes_strict_gate(baseline: ExperimentOutcome, candidate: ExperimentOutcome) -> bool:
    metrics, reference = candidate.metrics, baseline.metrics
    return metrics.recall_at_10 >= reference.recall_at_10 - 0.01 and metrics.mrr >= reference.mrr - 0.02 and metrics.ndcg_at_10 >= reference.ndcg_at_10 - 0.02 and metrics.precision_at_10 >= reference.precision_at_10 - 0.02


def select_rejection_policy(baseline: ExperimentOutcome, candidates: Iterable[ExperimentOutcome], strict: bool = True) -> ExperimentOutcome | None:
    candidates = list(candidates)
    eligible = [candidate for candidate in candidates if passes_strict_gate(baseline, candidate)] if strict else [candidate for candidate in candidates if candidate.metrics.recall_at_10 >= baseline.metrics.recall_at_10 - 0.01]
    improving = [candidate for candidate in eligible if candidate.metrics.hard_negative_false_positive_rate < baseline.metrics.hard_negative_false_positive_rate]
    if not improving:
        return None
    return min(improving, key=lambda candidate: (candidate.metrics.hard_negative_false_positive_rate, candidate.metrics.negative_false_positive_rate, -candidate.metrics.true_no_result_rate, _policy_complexity(candidate), candidate.effective_parameters.get("semanticOnlyMinimumSimilarity", BASELINE_MINIMUM_SIMILARITY)))


def _policy_complexity(candidate: ExperimentOutcome) -> int:
    return int("minimumSemanticGap" in candidate.effective_parameters) + int(candidate.effective_parameters.get("semanticOnlyMinimumSimilarity", BASELINE_MINIMUM_SIMILARITY) > BASELINE_MINIMUM_SIMILARITY)


def _ranking_json(ranking: QueryRanking, diagnostic: QueryDiagnostics) -> dict[str, Any]:
    return {"query": ranking.query.id, "text": ranking.query.text, "category": ranking.query.category, "split": ranking.query.split, "judgments": dict(ranking.query.judgments), "results": [asdict(result) for result in ranking.results], "totalMs": ranking.total_ms, "diagnostics": {"topSemanticScores": list(diagnostic.top_semantic_scores), "topSemanticGap": diagnostic.top_semantic_gap, "lexicalCandidateCount": diagnostic.lexical_candidate_count, "semanticCandidateCount": diagnostic.semantic_candidate_count, "finalCandidateCount": diagnostic.final_candidate_count, "agreementDocumentIds": sorted(diagnostic.agreement_document_ids)}}


def write_outcome(output_dir: Path, outcome: ExperimentOutcome) -> None:
    (output_dir / "raw").mkdir(parents=True, exist_ok=True)
    (output_dir / "per-query").mkdir(exist_ok=True)
    (output_dir / "raw" / f"{outcome.config.name}.json").write_text(json.dumps(list(outcome.raw_responses), indent=2), encoding="utf-8")
    (output_dir / "per-query" / f"{outcome.config.name}.json").write_text(json.dumps([_ranking_json(ranking, diagnostic) for ranking, diagnostic in zip(outcome.rankings, outcome.diagnostics, strict=True)], indent=2), encoding="utf-8")


def _summary_row(outcome: ExperimentOutcome) -> dict[str, Any]:
    return {"experiment": outcome.config.name, "mode": outcome.config.mode, **outcome.effective_parameters, **outcome.metrics.json()}


def write_summary_table(output_dir: Path, outcomes: Iterable[ExperimentOutcome], name: str = "metrics.csv") -> None:
    rows = [_summary_row(outcome) for outcome in outcomes]
    if not rows:
        return
    fields = list(dict.fromkeys(field for row in rows for field in row))
    with (output_dir / name).open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_hard_negative_mining(output_dir: Path, outcome: ExperimentOutcome) -> None:
    rows = []
    for ranking, diagnostic in zip(outcome.rankings, outcome.diagnostics, strict=True):
        if ranking.query.category == "HARD_NEGATIVE" and ranking.results:
            strongest = ranking.results[0]
            rows.append({"query": ranking.query.text, "falsePositive": strongest.document_id, "semanticSimilarity": diagnostic.semantic_scores.get(strongest.document_id), "lexicalHit": strongest.document_id in diagnostic.lexical_document_ids, "hybridRank": strongest.rank, "topSemanticGap": diagnostic.top_semantic_gap})
    with (output_dir / "hard-negative-mining.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=["query", "falsePositive", "semanticSimilarity", "lexicalHit", "hybridRank", "topSemanticGap"])
        writer.writeheader()
        writer.writerows(rows)


def write_diagnostics_table(output_dir: Path, outcome: ExperimentOutcome) -> None:
    rows = []
    for ranking, diagnostic in zip(outcome.rankings, outcome.diagnostics, strict=True):
        rows.append({
            "queryId": ranking.query.id, "category": ranking.query.category, "topSemanticScore": diagnostic.top_semantic_scores[0] if diagnostic.top_semantic_scores else None,
            "top2SemanticScore": diagnostic.top_semantic_scores[1] if len(diagnostic.top_semantic_scores) > 1 else None,
            "top3SemanticScore": diagnostic.top_semantic_scores[2] if len(diagnostic.top_semantic_scores) > 2 else None,
            "topSemanticGap": diagnostic.top_semantic_gap, "lexicalCandidateCount": diagnostic.lexical_candidate_count,
            "semanticCandidateCount": diagnostic.semantic_candidate_count, "finalCandidateCount": diagnostic.final_candidate_count,
            "returnedResultCount": len(ranking.results), "totalMs": ranking.total_ms,
        })
    _write_rows(output_dir / "query-diagnostics.csv", rows)


def write_agreement_table(output_dir: Path, outcome: ExperimentOutcome) -> None:
    rows = []
    for ranking, diagnostic in zip(outcome.rankings, outcome.diagnostics, strict=True):
        if not ranking.query.is_negative:
            continue
        for result in ranking.results:
            lexical = result.document_id in diagnostic.lexical_document_ids
            semantic = result.document_id in diagnostic.semantic_scores
            group = "LEXICAL_SEMANTIC" if lexical and semantic else "LEXICAL_ONLY" if lexical else "SEMANTIC_ONLY"
            rows.append({"queryId": ranking.query.id, "category": ranking.query.category, "group": group, "documentId": result.document_id, "semanticSimilarity": diagnostic.semantic_scores.get(result.document_id), "rank": result.rank})
    _write_rows(output_dir / "agreement-false-positives.csv", rows)


def _write_rows(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = list(dict.fromkeys(field for row in rows for field in row))
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=fields or ["empty"])
        writer.writeheader()
        writer.writerows(rows)


def write_distribution_chart(path: Path, outcome: ExperimentOutcome) -> None:
    colors = {"POSITIVE": "#2e7d32", "EASY_NEGATIVE": "#1565c0", "DOMAIN_NEGATIVE": "#ef6c00", "HARD_NEGATIVE": "#c62828"}
    points = []
    for ranking, diagnostic in zip(outcome.rankings, outcome.diagnostics, strict=True):
        if ranking.query.is_negative and diagnostic.top_semantic_scores:
            points.append((ranking.query.category, diagnostic.top_semantic_scores[0]))
        elif not ranking.query.is_negative:
            relevant_scores = [score for document_id, score in diagnostic.semantic_scores.items() if ranking.query.grade_for(document_id) >= 2]
            if relevant_scores:
                points.append(("POSITIVE", max(relevant_scores)))
    width, height = 820, 390
    low, high = min((score for _, score in points), default=0.0), max((score for _, score in points), default=1.0)
    span = high - low or 1.0
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">', '<rect width="100%" height="100%" fill="white"/>', '<text x="45" y="28" font-family="sans-serif" font-size="18">Top semantic-score distribution</text>']
    for index, category in enumerate(("POSITIVE", "EASY_NEGATIVE", "DOMAIN_NEGATIVE", "HARD_NEGATIVE")):
        y = 85 + index * 75
        parts.append(f'<text x="45" y="{y}" font-family="sans-serif" font-size="13">{category}</text><line x1="220" y1="{y}" x2="770" y2="{y}" stroke="#ddd"/>')
        for point_index, (point_category, score) in enumerate(points):
            if point_category == category:
                x = 220 + (score - low) / span * 550
                parts.append(f'<circle cx="{x:.1f}" cy="{y}" r="5" fill="{colors[category]}"/>')
    parts.append(f'<text x="220" y="365" font-family="sans-serif" font-size="12">semantic similarity range: {low:.3f} to {high:.3f}</text></svg>')
    path.write_text("\n".join(parts), encoding="utf-8")


def write_threshold_chart(path: Path, outcomes: Iterable[ExperimentOutcome]) -> None:
    outcomes = list(outcomes)
    width, height, left, bottom = 820, 350, 80, 55
    values = [float(outcome.effective_parameters["minimumSimilarity"]) for outcome in outcomes]
    x = lambda value: left + (value - min(values)) / (max(values) - min(values) or 1) * (width - left - 35)
    y = lambda value: height - bottom - value * (height - bottom - 45)
    series = {"Recall@10": ("#1565c0", [outcome.metrics.recall_at_10 for outcome in outcomes]), "Hard-negative FP": ("#c62828", [outcome.metrics.hard_negative_false_positive_rate for outcome in outcomes])}
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">', '<rect width="100%" height="100%" fill="white"/>', '<text x="80" y="28" font-family="sans-serif" font-size="18">Threshold trade-off</text>', f'<line x1="{left}" y1="{height-bottom}" x2="{width-35}" y2="{height-bottom}" stroke="#555"/>']
    for name, (color, metric_values) in series.items():
        points = " ".join(f"{x(value):.1f},{y(metric):.1f}" for value, metric in zip(values, metric_values, strict=True))
        parts.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="3"/><text x="{left + list(series).index(name) * 180}" y="{height - 12}" fill="{color}" font-family="sans-serif" font-size="13">{name}</text>')
    for value in values:
        parts.append(f'<text x="{x(value)-10:.1f}" y="{height-bottom+20}" font-family="sans-serif" font-size="12">{value:.2f}</text>')
    parts.append("</svg>")
    path.write_text("\n".join(parts), encoding="utf-8")


def write_policy_chart(path: Path, outcomes: Iterable[ExperimentOutcome]) -> None:
    outcomes = list(outcomes)
    width, row_height, height = 1020, 28, 70 + 28 * len(outcomes)
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">', '<rect width="100%" height="100%" fill="white"/>', '<text x="25" y="30" font-family="sans-serif" font-size="18">No-result policy comparison</text>', '<text x="25" y="55" font-family="sans-serif" font-size="12">Policy</text><text x="410" y="55" font-family="sans-serif" font-size="12">Recall@10</text><text x="510" y="55" font-family="sans-serif" font-size="12">Overall FP</text><text x="620" y="55" font-family="sans-serif" font-size="12">Hard FP</text><text x="710" y="55" font-family="sans-serif" font-size="12">True no-result</text>']
    for index, outcome in enumerate(outcomes, start=1):
        y, metrics = 55 + row_height * index, outcome.metrics
        parts.append(f'<text x="25" y="{y}" font-family="monospace" font-size="12">{escape(outcome.config.name)}</text><text x="425" y="{y}" font-family="monospace" font-size="12">{metrics.recall_at_10:.3f}</text><text x="530" y="{y}" font-family="monospace" font-size="12">{metrics.negative_false_positive_rate:.3f}</text><text x="640" y="{y}" font-family="monospace" font-size="12">{metrics.hard_negative_false_positive_rate:.3f}</text><text x="760" y="{y}" font-family="monospace" font-size="12">{metrics.true_no_result_rate:.3f}</text>')
    parts.append("</svg>")
    path.write_text("\n".join(parts), encoding="utf-8")


def run(args: argparse.Namespace) -> None:
    dataset = load_dataset(args.dataset)
    output_dir, output_root = args.output.resolve(), (Path(__file__).parent / "output").resolve()
    if not output_dir.is_relative_to(output_root) or output_dir == output_root:
        raise ValueError(f"output must be a subdirectory of {output_root}")
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)
    client = ApiClient(args.base_url, args.timeout)
    runtime_ids = create_corpus(client, dataset)
    (output_dir / "runtime-document-ids.json").write_text(json.dumps(runtime_ids, indent=2), encoding="utf-8")
    tuning, holdout = dataset.queries_for("TUNING"), dataset.queries_for("HOLDOUT")
    baseline = run_experiment(client, tuning, runtime_ids, ExperimentConfig("baseline-hybrid", "HYBRID", {}))
    mode_outcomes = [run_experiment(client, tuning, runtime_ids, ExperimentConfig(f"mode-{mode.lower()}", mode, {})) for mode in ("LEXICAL", "SEMANTIC", "HYBRID")]
    semantic_distribution = run_experiment(client, tuning, runtime_ids, ExperimentConfig("semantic-distribution", "SEMANTIC", {"minimumSimilarity": -1.0, "candidateLimit": 200}))
    threshold_outcomes = [run_experiment(client, tuning, runtime_ids, ExperimentConfig(f"threshold-{threshold:.2f}", "HYBRID", {"minimumSimilarity": threshold})) for threshold in THRESHOLDS]
    policy_outcomes = [apply_rejection_policy(baseline, policy) for policy in policy_candidates(baseline)]
    rejection_outcomes = [*threshold_outcomes, *policy_outcomes]
    selected = select_rejection_policy(baseline, rejection_outcomes)
    recall_only = None if selected else select_rejection_policy(baseline, rejection_outcomes, strict=False)
    holdout_baseline = run_experiment(client, holdout, runtime_ids, ExperimentConfig("holdout-baseline", "HYBRID", {}))
    if selected and selected.config.name.startswith("threshold-"):
        holdout_final = run_experiment(client, holdout, runtime_ids, ExperimentConfig("holdout-final", "HYBRID", selected.config.overrides))
    else:
        selected_policy = selected.config.overrides if selected else RejectionPolicy("policy-baseline").parameters()
        holdout_final = apply_rejection_policy(holdout_baseline, RejectionPolicy("holdout-final", float(selected_policy.get("semanticOnlyMinimumSimilarity", BASELINE_MINIMUM_SIMILARITY)), selected_policy.get("minimumSemanticGap")))
    all_outcomes = [baseline, *mode_outcomes, semantic_distribution, *threshold_outcomes, *policy_outcomes, holdout_baseline, holdout_final]
    for outcome in all_outcomes:
        write_outcome(output_dir, outcome)
    write_summary_table(output_dir, all_outcomes)
    write_summary_table(output_dir, rejection_outcomes, "rejection-strategy-comparison.csv")
    write_hard_negative_mining(output_dir, baseline)
    write_diagnostics_table(output_dir, baseline)
    write_agreement_table(output_dir, baseline)
    charts = output_dir / "charts"
    charts.mkdir(exist_ok=True)
    write_policy_chart(charts / "rejection-policy-comparison.svg", [baseline, *rejection_outcomes])
    write_distribution_chart(charts / "negative-score-distribution.svg", baseline)
    write_threshold_chart(charts / "threshold-trade-off.svg", threshold_outcomes)
    (output_dir / "recommendations.json").write_text(json.dumps({"strictGate": "Recall@10 >= baseline - 0.01; MRR, NDCG@10 and Precision@10 >= baseline - 0.02.", "baseline": _summary_row(baseline), "selectedStrictPolicy": _summary_row(selected) if selected else None, "recallOnlySensitivity": _summary_row(recall_only) if recall_only else None, "holdoutBaseline": _summary_row(holdout_baseline), "holdoutFinal": _summary_row(holdout_final)}, indent=2), encoding="utf-8")
    (output_dir / "run-metadata.json").write_text(json.dumps({"runAt": datetime.now(UTC).isoformat(), "baseUrl": args.base_url, "documentCount": len(dataset.documents), "tuningQueryCount": len(tuning), "holdoutQueryCount": len(holdout)}, indent=2), encoding="utf-8")
    print(f"Evaluation complete. Review {output_dir / 'metrics.csv'} and {output_dir / 'recommendations.json'}.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate Nevis no-result behavior through the evaluation-profile HTTP API")
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
