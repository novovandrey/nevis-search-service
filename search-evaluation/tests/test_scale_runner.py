import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parents[1]))

from scale_runner import DIMENSION, generate_batches, generated_vector_checksum, normalized


class ScaleRunnerTest(unittest.TestCase):
    def test_vector_generation_is_deterministic_finite_normalized_and_has_expected_distribution(self) -> None:
        first = generated_vector_checksum(200)
        second = generated_vector_checksum(200)
        self.assertEqual(first, second)

        batches, centroids = generate_batches(200, batch_size=31)
        materialized = list(batches)
        vectors = np.concatenate([batch.vectors for batch in materialized])
        document_ids = np.concatenate([batch.document_ids for batch in materialized])
        self.assertEqual(vectors.shape, (200, DIMENSION))
        self.assertTrue(np.isfinite(vectors).all())
        np.testing.assert_allclose(np.linalg.norm(vectors, axis=1), 1.0, atol=1e-5)
        counts = np.unique(document_ids, return_counts=True)[1]
        self.assertGreaterEqual(counts.min(), 1)
        self.assertLessEqual(counts.max(), 20)
        self.assertGreater(len(centroids), 0)

    def test_normalization_rejects_zero_and_non_finite_vectors(self) -> None:
        with self.assertRaisesRegex(ValueError, "non-finite or zero"):
            normalized(np.zeros((1, DIMENSION), dtype=np.float32))
        invalid = np.ones((1, DIMENSION), dtype=np.float32)
        invalid[0, 0] = np.nan
        with self.assertRaisesRegex(ValueError, "non-finite or zero"):
            normalized(invalid)


if __name__ == "__main__":
    unittest.main()
