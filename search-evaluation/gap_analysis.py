from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from dataset import EvaluationQuery, load_dataset
from metrics import MetricsSummary, QueryRanking, RankedDocument, summarize
from quality_runner import case_metrics, passes_threshold_gate


@dataclass(frozen=True)
class GapOutcome:
    gap: float
    metrics: MetricsSummary
    responses: tuple[dict[str, Any], ...]


def semantic_gap(response: Mapping[str, Any]) -> float:
    semantic = response["semantic"]
    if len(semantic) < 2:
        return 0.0
    return float(semantic[0]["similarity"]) - float(semantic[1]["similarity"])


def gap_candidates(responses: Sequence[Mapping[str, Any]], maximum: int = 21) -> tuple[float, ...]:
    observed = sorted({round(semantic_gap(response), 6) for response in responses})
    if not observed:
        return (0.0,)
    values = {0.0}
    count = min(maximum - 1, len(observed))
    for index in range(count):
        position = index * (len(observed) - 1) / max(count - 1, 1)
        lower = int(position)
        upper = min(lower + 1, len(observed) - 1)
        fraction = position - lower
        values.add(round(observed[lower] + (observed[upper] - observed[lower]) * fraction, 6))
    return tuple(sorted(values))


def apply_gap(response: Mapping[str, Any], minimum_gap: float) -> dict[str, Any]:
    lexical_ids = {item["documentId"] for item in response["lexical"]}
    keep_semantic_only = semantic_gap(response) >= minimum_gap
    filtered = [
        dict(item)
        for item in response["final"]
        if item["documentId"] in lexical_ids or keep_semantic_only
    ]
    for rank, item in enumerate(filtered, start=1):
        item["rank"] = rank
    result = dict(response)
    result["final"] = filtered
    return result


def evaluate_gap(
        queries: Sequence[EvaluationQuery],
        responses: Sequence[Mapping[str, Any]],
        aliases: Mapping[str, str],
        gap: float,
) -> GapOutcome:
    filtered = tuple(apply_gap(response, gap) for response in responses)
    rankings = []
    for query, response in zip(queries, filtered, strict=True):
        results = tuple(
            RankedDocument(aliases[item["documentId"]], item["rank"], item["rrfScore"])
            for item in response["final"]
        )
        rankings.append(QueryRanking(query, results, int(response["timings"]["totalMs"])))
    return GapOutcome(gap, summarize(rankings), filtered)


def select_gap(baseline: GapOutcome, outcomes: Sequence[GapOutcome]) -> GapOutcome | None:
    eligible = [
        outcome for outcome in outcomes
        if outcome.gap > 0.0
        and passes_threshold_gate(baseline.metrics, outcome.metrics)
        and outcome.metrics.hard_negative_false_positive_rate
        < baseline.metrics.hard_negative_false_positive_rate
    ]
    if not eligible:
        return None
    return min(eligible, key=lambda outcome: (
        outcome.metrics.hard_negative_false_positive_rate,
        outcome.metrics.negative_false_positive_rate,
        -outcome.metrics.precision_at_10,
        outcome.gap,
    ))


def run(args: argparse.Namespace) -> None:
    dataset = load_dataset(args.dataset)
    queries = tuple(query for query in dataset.queries if query.split.lower() == args.split)
    runtime_ids = json.loads(args.runtime_ids.read_text(encoding="utf-8"))
    aliases = {runtime_id: alias for alias, runtime_id in runtime_ids.items()}
    threshold_runs = json.loads(args.responses.read_text(encoding="utf-8"))
    responses = tuple(threshold_runs[f"{args.threshold:.6f}"])

    baseline = evaluate_gap(queries, responses, aliases, 0.0)
    if args.fixed_gap is None:
        candidates = gap_candidates(responses)
        outcomes = tuple(evaluate_gap(queries, responses, aliases, gap) for gap in candidates)
        selected = select_gap(baseline, outcomes)
        mode = "tuning-selection"
    else:
        outcomes = (baseline, evaluate_gap(queries, responses, aliases, args.fixed_gap))
        selected = outcomes[1]
        mode = "fixed-holdout-verification"

    selected_for_cases = selected or baseline
    case_values, failures = case_metrics(queries, selected_for_cases.responses, aliases)
    payload = {
        "mode": mode,
        "split": args.split,
        "threshold": args.threshold,
        "baselineMetrics": baseline.metrics.json(),
        "selectedGap": None if selected is None else selected.gap,
        "selectedMetrics": None if selected is None else selected.metrics.json(),
        "selectedPassesQualityGate": None if selected is None else passes_threshold_gate(
            baseline.metrics, selected.metrics,
        ),
        "candidateOutcomes": [
            {"gap": outcome.gap, **outcome.metrics.json()} for outcome in outcomes
        ],
        "caseMetrics": case_values,
        "positiveTop10Failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps({
        "output": str(args.output.resolve()),
        "selectedGap": payload["selectedGap"],
        "selectedMetrics": payload["selectedMetrics"],
    }, indent=2))


def parse_args() -> argparse.Namespace:
    base = Path(__file__).parent
    parser = argparse.ArgumentParser(description="Evaluate a new semantic score-gap on saved Java responses")
    parser.add_argument("--dataset", type=Path, default=base / "data" / "long-document-dataset.json")
    parser.add_argument("--runtime-ids", type=Path, required=True)
    parser.add_argument("--responses", type=Path, required=True)
    parser.add_argument("--threshold", type=float, required=True)
    parser.add_argument("--split", choices=("tuning", "holdout"), required=True)
    parser.add_argument("--fixed-gap", type=float)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
