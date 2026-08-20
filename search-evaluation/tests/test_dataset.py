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
