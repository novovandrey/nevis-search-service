from __future__ import annotations

from dataclasses import asdict, dataclass
from math import log2
from statistics import mean
from typing import Iterable, Sequence

from dataset import EvaluationQuery

RELEVANT_GRADE = 2


@dataclass(frozen=True)
class RankedDocument:
    document_id: str
    rank: int
    score: float


@dataclass(frozen=True)
class QueryRanking:
    query: EvaluationQuery
    results: tuple[RankedDocument, ...]
    total_ms: int


@dataclass(frozen=True)
class MetricsSummary:
    recall_at_5: float
    recall_at_10: float
    precision_at_10: float
    mrr: float
    ndcg_at_10: float
    zero_result_rate: float
    negative_false_positive_rate: float
    true_no_result_rate: float
    easy_negative_false_positive_rate: float
    domain_negative_false_positive_rate: float
    hard_negative_false_positive_rate: float
    mean_latency_ms: float
    p50_latency_ms: float
    p95_latency_ms: float
    positive_query_count: int
    negative_query_count: int

    def json(self) -> dict[str, float | int]:
        return asdict(self)


def summarize(rankings: Sequence[QueryRanking]) -> MetricsSummary:
    if not rankings:
        raise ValueError("at least one ranking is required")
    positives = [ranking for ranking in rankings if ranking.query.has_relevant_document]
    negatives = [ranking for ranking in rankings if ranking.query.is_negative]
    graded = [ranking for ranking in rankings if any(grade > 0 for grade in ranking.query.judgments.values())]
    latencies = sorted(ranking.total_ms for ranking in rankings)
    return MetricsSummary(
        recall_at_5=_average(_recall_at(ranking, 5) for ranking in positives),
        recall_at_10=_average(_recall_at(ranking, 10) for ranking in positives),
        precision_at_10=_average(_precision_at(ranking, 10) for ranking in positives),
        mrr=_average(_reciprocal_rank(ranking) for ranking in positives),
        ndcg_at_10=_average(_ndcg_at(ranking, 10) for ranking in graded),
        zero_result_rate=sum(not ranking.results for ranking in rankings) / len(rankings),
        negative_false_positive_rate=_average(bool(ranking.results) for ranking in negatives),
        true_no_result_rate=_average(not ranking.results for ranking in negatives),
        easy_negative_false_positive_rate=_negative_false_positive_rate(rankings, "EASY_NEGATIVE"),
        domain_negative_false_positive_rate=_negative_false_positive_rate(rankings, "DOMAIN_NEGATIVE"),
        hard_negative_false_positive_rate=_negative_false_positive_rate(rankings, "HARD_NEGATIVE"),
        mean_latency_ms=mean(latencies),
        p50_latency_ms=_percentile(latencies, 0.50),
        p95_latency_ms=_percentile(latencies, 0.95),
        positive_query_count=len(positives),
        negative_query_count=len(negatives),
    )


def _recall_at(ranking: QueryRanking, k: int) -> float:
    relevant = sum(grade >= RELEVANT_GRADE for grade in ranking.query.judgments.values())
    hits = sum(ranking.query.grade_for(result.document_id) >= RELEVANT_GRADE for result in ranking.results[:k])
    return hits / relevant if relevant else 0.0


def _precision_at(ranking: QueryRanking, k: int) -> float:
    results = ranking.results[:k]
    if not results:
        return 0.0
    hits = sum(ranking.query.grade_for(result.document_id) >= RELEVANT_GRADE for result in results)
    return hits / len(results)


def _reciprocal_rank(ranking: QueryRanking) -> float:
    for result in ranking.results:
        if ranking.query.grade_for(result.document_id) >= RELEVANT_GRADE:
            return 1.0 / result.rank
    return 0.0


def _ndcg_at(ranking: QueryRanking, k: int) -> float:
    actual = _discounted_gain(ranking.query.grade_for(result.document_id) for result in ranking.results[:k])
    ideal = _discounted_gain(sorted(ranking.query.judgments.values(), reverse=True)[:k])
    return actual / ideal if ideal else 0.0


def _discounted_gain(grades: Iterable[int]) -> float:
    return sum((2**grade - 1) / log2(index + 2) for index, grade in enumerate(grades))


def _average(values: Iterable[float | bool]) -> float:
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def _percentile(sorted_values: Sequence[int], quantile: float) -> float:
    if not sorted_values:
        return 0.0
    index = (len(sorted_values) - 1) * quantile
    lower = int(index)
    upper = min(lower + 1, len(sorted_values) - 1)
    fraction = index - lower
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * fraction


def _negative_false_positive_rate(rankings: Sequence[QueryRanking], category: str) -> float:
    return _average(bool(ranking.results) for ranking in rankings if ranking.query.category == category)
