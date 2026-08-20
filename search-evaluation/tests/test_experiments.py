import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from dataset import EvaluationQuery
from evaluate import ExperimentConfig, ExperimentOutcome, QueryDiagnostics, RejectionPolicy, apply_rejection_policy, select_rejection_policy
from metrics import MetricsSummary, QueryRanking, RankedDocument


class ExperimentSelectionTest(unittest.TestCase):
    def test_policy_drops_weak_semantic_only_result_and_keeps_lexical_result(self) -> None:
        positive = EvaluationQuery("positive", "passport", "EXACT_LEXICAL", "TUNING", {"passport": 3})
        negative = EvaluationQuery("negative", "licence", "HARD_NEGATIVE", "TUNING", {})
        baseline = ExperimentOutcome(
            ExperimentConfig("baseline", "HYBRID", {}), {"minimumSimilarity": 0.30},
            self.metrics(1.0, 1.0, 1.0, 1.0),
            (QueryRanking(positive, (RankedDocument("passport", 1, 1.0),), 1), QueryRanking(negative, (RankedDocument("identity-card", 1, 0.1),), 1)),
            (QueryDiagnostics(frozenset({"passport"}), {"passport": 0.35}, (0.35,), None, 1, 1, 1), QueryDiagnostics(frozenset(), {"identity-card": 0.31}, (0.31,), None, 0, 1, 1)), (),
        )
        filtered = apply_rejection_policy(baseline, RejectionPolicy("policy", 0.32))
        self.assertEqual(filtered.rankings[0].results[0].document_id, "passport")
        self.assertEqual(filtered.rankings[1].results, ())

    def test_strict_selection_rejects_quality_regression(self) -> None:
        baseline = self.create_outcome("baseline", 0.90, 0.70, 0.70, 0.30, 1.0)
        regression = self.create_outcome("regression", 0.80, 0.99, 0.99, 0.99, 0.0)
        improvement = self.create_outcome("improvement", 0.90, 0.70, 0.70, 0.30, 0.0)
        self.assertEqual(select_rejection_policy(baseline, [regression, improvement]).config.name, "improvement")

    def create_outcome(self, name: str, recall: float, ndcg: float, mrr: float, precision: float, hard_fp: float) -> ExperimentOutcome:
        query = EvaluationQuery("query", "query", "EXACT_LEXICAL", "TUNING", {"document": 3})
        metrics = self.metrics(recall, ndcg, mrr, precision, hard_fp)
        return ExperimentOutcome(
            ExperimentConfig(name, "HYBRID", {}),
            {"candidateLimit": 50, "rrfK": 60, "minimumSimilarity": 0.30, "lexicalWeight": 1.25, "vectorWeight": 1.0},
            metrics,
            (QueryRanking(query, (), 1),), (),
            (),
        )

    def metrics(self, recall: float, ndcg: float, mrr: float, precision: float, hard_fp: float = 0.0) -> MetricsSummary:
        return MetricsSummary(recall, recall, precision, mrr, ndcg, 0.0, hard_fp, 1.0 - hard_fp, hard_fp, hard_fp, hard_fp, 1.0, 1.0, 1.0, 1, 1)
