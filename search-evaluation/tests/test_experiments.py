import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from dataset import EvaluationQuery
from evaluate import ExperimentConfig, ExperimentOutcome, choose_quality_preserving
from metrics import MetricsSummary, QueryRanking


class ExperimentSelectionTest(unittest.TestCase):
    def test_preserves_recall_then_prefers_precision_and_negative_query_behavior(self) -> None:
        baseline = self.create_outcome("baseline", 0.90, 0.70, 0.70, 0.30)
        recall_regression = self.create_outcome("regression", 0.80, 0.99, 0.99, 0.99)
        lower_noise = self.create_outcome("lower-noise", 0.90, 0.80, 0.80, 0.40)

        chosen = choose_quality_preserving(baseline, [recall_regression, lower_noise])

        self.assertEqual(chosen.config.name, "lower-noise")

    def create_outcome(self, name: str, recall: float, ndcg: float, mrr: float, precision: float) -> ExperimentOutcome:
        query = EvaluationQuery("query", "query", "EXACT_LEXICAL", "TUNING", {"document": 3})
        metrics = MetricsSummary(
            recall, recall, precision, mrr, ndcg, 0.0, 0.0, 1.0, 1.0, 1.0, 1, 0
        )
        return ExperimentOutcome(
            ExperimentConfig(name, "HYBRID", {}),
            {"candidateLimit": 50, "rrfK": 60, "minimumSimilarity": 0.30, "lexicalWeight": 1.25, "vectorWeight": 1.0},
            metrics,
            (QueryRanking(query, (), 1),),
            (),
        )
