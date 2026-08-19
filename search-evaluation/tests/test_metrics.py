import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from dataset import EvaluationQuery
from metrics import QueryRanking, RankedDocument, summarize


class MetricsTest(unittest.TestCase):
    def test_calculates_rank_metrics_with_multiple_graded_relevant_documents(self) -> None:
        query = EvaluationQuery("query", "query", "EXACT_LEXICAL", "TUNING", {"high": 3, "relevant": 2, "weak": 1})
        summary = summarize([QueryRanking(query, (
            RankedDocument("weak", 1, 0.9),
            RankedDocument("high", 2, 0.8),
            RankedDocument("irrelevant", 3, 0.7),
        ), 12)])

        self.assertEqual(summary.recall_at_5, 0.5)
        self.assertEqual(summary.recall_at_10, 0.5)
        self.assertEqual(summary.precision_at_10, 1 / 3)
        self.assertEqual(summary.mrr, 0.5)
        self.assertGreater(summary.ndcg_at_10, 0.5)
        self.assertEqual(summary.zero_result_rate, 0.0)

    def test_handles_no_results_and_negative_queries_explicitly(self) -> None:
        positive = EvaluationQuery("positive", "passport", "EXACT_LEXICAL", "TUNING", {"passport": 3})
        negative = EvaluationQuery("negative", "moon", "NEGATIVE", "TUNING", {})
        summary = summarize([
            QueryRanking(positive, (), 4),
            QueryRanking(negative, (), 6),
        ])

        self.assertEqual(summary.recall_at_10, 0.0)
        self.assertEqual(summary.mrr, 0.0)
        self.assertEqual(summary.ndcg_at_10, 0.0)
        self.assertEqual(summary.zero_result_rate, 1.0)
        self.assertEqual(summary.negative_false_positive_rate, 0.0)
        self.assertEqual(summary.p50_latency_ms, 5.0)
