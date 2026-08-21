import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

import psycopg
from pgvector.psycopg import register_vector

from scale_runner import generate_batches


@unittest.skipUnless(os.environ.get("NEVIS_TEST_DSN"), "NEVIS_TEST_DSN is not configured")
class ScaleCopySmokeTest(unittest.TestCase):
    def test_binary_copy_round_trip_for_small_vector_batch(self) -> None:
        batches, _ = generate_batches(17, batch_size=7)
        with psycopg.connect(os.environ["NEVIS_TEST_DSN"]) as connection:
            register_vector(connection)
            with connection.cursor() as cursor:
                cursor.execute("CREATE TEMP TABLE scale_copy_smoke (document_id bigint, chunk_index smallint, embedding vector(384))")
                with cursor.copy(
                    "COPY scale_copy_smoke (document_id, chunk_index, embedding) FROM STDIN (FORMAT BINARY)"
                ) as copy:
                    copy.set_types(["int8", "int2", "vector"])
                    for batch in batches:
                        for document_id, chunk_index, vector in zip(
                                batch.document_ids, batch.chunk_indexes, batch.vectors, strict=True
                        ):
                            copy.write_row((int(document_id), int(chunk_index), vector))
                cursor.execute("SELECT count(*), min(vector_dims(embedding)), max(vector_dims(embedding)) FROM scale_copy_smoke")
                self.assertEqual(cursor.fetchone(), (17, 384, 384))
