import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from metrics import MetricsSummary
from dataset import EvaluationQuery
from gap_analysis import apply_gap, gap_candidates
from quality_runner import ThresholdOutcome, ann_fidelity, case_metrics, quantile_threshold_candidates, select_threshold_candidate


class QualityRunnerTest(unittest.TestCase):
    def test_quantile_thresholds_are_deterministic_bounded_and_include_baselines(self) -> None:
        values = [index / 100 for index in range(100)]
        first = quantile_threshold_candidates(values)
        second = quantile_threshold_candidates(reversed(values))
        self.assertEqual(first, second)
        self.assertLessEqual(len(first), 21)
        self.assertIn(-1.0, first)
        self.assertIn(0.30, first)

    def test_threshold_selection_applies_quality_gates_then_minimizes_negative_noise(self) -> None:
        baseline = ThresholdOutcome(-1.0, self.metrics(0.90, 0.80, 0.80, 0.50, 1.0, 1.0))
        regression = ThresholdOutcome(0.60, self.metrics(0.80, 0.90, 0.90, 0.90, 0.0, 0.0))
        eligible = ThresholdOutcome(0.40, self.metrics(0.90, 0.79, 0.79, 0.49, 0.0, 0.2))
        noisier = ThresholdOutcome(0.35, self.metrics(0.90, 0.80, 0.80, 0.50, 0.2, 0.2))
        self.assertEqual(select_threshold_candidate([baseline, regression, eligible, noisier]), eligible)

    def test_ann_fidelity_uses_chunk_identity_and_document_collapse(self) -> None:
        exact = [self.response([("a", 0), ("a", 1), ("b", 0)], ["a", "b"], 10)]
        approximate = [self.response([("a", 0), ("b", 0), ("c", 0)], ["a", "c"], 4)]
        result = ann_fidelity(exact, approximate)
        self.assertAlmostEqual(result.chunk_recall, 2 / 3)
        self.assertEqual(result.document_recall_at_50, 0.5)
        self.assertEqual(result.top_10_overlap, 0.5)
        self.assertEqual(result.p50_ms, 4.0)

    def test_case_metrics_report_boundary_recall_and_missing_relevant_documents(self) -> None:
        query = EvaluationQuery(
            "boundary", "boundary phrase", "NATURAL_LANGUAGE", "TUNING",
            {"expected": 3}, ("after_boundary",),
        )
        response = {
            "final": [{"documentId": "runtime-other", "rank": 1, "rrfScore": 0.1}],
            "timings": {"totalMs": 1},
        }
        metrics, failures = case_metrics((query,), (response,), {"runtime-other": "other"})
        self.assertEqual(metrics["after_boundary"]["recallAt10"], 0.0)
        self.assertEqual(failures[0]["missingRelevantDocuments"], ["expected"])

    def test_gap_policy_keeps_lexical_results_when_semantic_gap_is_too_small(self) -> None:
        response = {
            "lexical": [{"documentId": "lexical"}],
            "semantic": [
                {"documentId": "semantic", "similarity": 0.81},
                {"documentId": "lexical", "similarity": 0.80},
            ],
            "final": [
                {"documentId": "semantic", "rank": 1, "rrfScore": 0.2},
                {"documentId": "lexical", "rank": 2, "rrfScore": 0.1},
            ],
        }
        filtered = apply_gap(response, 0.02)
        self.assertEqual(filtered["final"], [
            {"documentId": "lexical", "rank": 1, "rrfScore": 0.1},
        ])
        self.assertIn(0.0, gap_candidates((response,)))

    def response(self, chunks: list[tuple[str, int]], documents: list[str], latency: int) -> dict:
        return {
            "chunks": [{"documentId": document, "chunkIndex": index} for document, index in chunks],
            "semantic": [{"documentId": document} for document in documents],
            "timings": {"semanticMs": latency},
            "diagnostics": {"distinctDocuments": 3, "maximumChunksPerDocument": 1, "concentration": 1 / 3},
        }

    def metrics(self, recall: float, ndcg: float, mrr: float, precision: float, hard_fp: float, overall_fp: float) -> MetricsSummary:
        return MetricsSummary(
            recall, recall, precision, mrr, ndcg, 0.0, overall_fp, 1 - overall_fp,
            overall_fp, overall_fp, hard_fp, 1.0, 1.0, 1.0, 1, 1,
        )


if __name__ == "__main__":
    unittest.main()
