import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from dataset import EvaluationDataset, EvaluationDocument, EvaluationQuery, load_dataset, validate


class DatasetTest(unittest.TestCase):
    def test_loads_the_checked_in_benchmark_with_both_splits(self) -> None:
        dataset = load_dataset(Path(__file__).parents[1] / "data" / "dataset.json")

        self.assertGreaterEqual(len(dataset.documents), 10)
        self.assertTrue(dataset.queries_for("TUNING"))
        self.assertTrue(dataset.queries_for("HOLDOUT"))
        self.assertTrue(any(query.is_negative for query in dataset.queries))
        self.assertTrue({query.id for query in dataset.queries_for("TUNING")}.isdisjoint(
            {query.id for query in dataset.queries_for("HOLDOUT")}
        ))

    def test_rejects_a_positive_query_without_a_relevant_judgment(self) -> None:
        dataset = EvaluationDataset(
            documents=(EvaluationDocument("one", "One", "One"),),
            queries=(
                EvaluationQuery("tuning", "one", "EXACT_LEXICAL", "TUNING", {}),
                EvaluationQuery("holdout", "nothing", "EASY_NEGATIVE", "HOLDOUT", {}),
            ),
        )

        with self.assertRaisesRegex(ValueError, "grade 2 or 3"):
            validate(dataset)

    def test_rejects_relevant_judgment_for_every_negative_category(self) -> None:
        for category in ("EASY_NEGATIVE", "DOMAIN_NEGATIVE", "HARD_NEGATIVE"):
            dataset = EvaluationDataset(
                documents=(EvaluationDocument("one", "One", "One"),),
                queries=(
                    EvaluationQuery("tuning", "one", "EXACT_LEXICAL", "TUNING", {"one": 3}),
                    EvaluationQuery("holdout", "nothing", category, "HOLDOUT", {"one": 2}),
                ),
            )
            with self.assertRaisesRegex(ValueError, "must not have relevant"):
                validate(dataset)

    def test_long_document_dataset_has_exact_shape_sizes_splits_and_case_annotations(self) -> None:
        dataset = load_dataset(Path(__file__).parents[1] / "data" / "long-document-dataset.json")

        self.assertEqual(len(dataset.documents), 72)
        self.assertEqual(len(dataset.queries), 120)
        self.assertEqual(len(dataset.queries_for("TUNING")), 84)
        self.assertEqual(len(dataset.queries_for("HOLDOUT")), 36)
        self.assertEqual(sum(not query.is_negative for query in dataset.queries), 90)
        self.assertEqual(sum(query.is_negative for query in dataset.queries), 30)
        self.assertEqual(sum(document.size_class == "standard-8-12kb" for document in dataset.documents), 60)
        self.assertEqual(sum(document.size_class == "large-20-40kb" for document in dataset.documents), 10)
        self.assertEqual(sum(document.size_class == "stress-near-50000" for document in dataset.documents), 2)
        self.assertTrue(all(8_000 <= len(document.content) <= 12_000 for document in dataset.documents[:60]))
        self.assertTrue(all(20_000 <= len(document.content) <= 40_000 for document in dataset.documents[60:70]))
        self.assertTrue(all(49_000 <= len(document.content) <= 50_000 for document in dataset.documents[70:]))
        tags = {tag for document in dataset.documents for tag in document.tags}
        self.assertTrue({
            "start", "middle", "end", "before_boundary", "after_boundary", "oversized_paragraph",
            "token_fallback_sentence", "misleading_title", "multi_topic", "duplicate_near_duplicate",
            "multiple_relevant_chunks",
        }.issubset(tags))
