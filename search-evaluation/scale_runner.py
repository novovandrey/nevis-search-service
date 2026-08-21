from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from statistics import mean
from typing import Iterator, Sequence

import numpy as np
import psycopg
from pgvector.psycopg import register_vector


DIMENSION = 384
SEED = 20260821
TABLE = "evaluation_scale_chunks"
INDEX = "evaluation_scale_chunks_embedding_hnsw_idx"


@dataclass(frozen=True)
class GeneratedBatch:
    document_ids: np.ndarray
    chunk_indexes: np.ndarray
    vectors: np.ndarray


@dataclass(frozen=True)
class Retrieval:
    chunk_hits: tuple[tuple[int, int], ...]
    document_hits: tuple[int, ...]
    distinct_documents: int
    latency_ms: float


@dataclass(frozen=True)
class ConfigurationResult:
    chunk_candidate_limit: int
    hnsw_ef_search: int
    chunk_recall: float
    document_recall_at_50: float
    distinct_documents: float
    exact_p50_ms: float
    exact_p95_ms: float
    hnsw_p50_ms: float
    hnsw_p95_ms: float
    hnsw_p99_ms: float


def normalized(values: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(values, axis=-1, keepdims=True)
    if not np.all(np.isfinite(norms)) or np.any(norms == 0):
        raise ValueError("cannot normalize non-finite or zero vectors")
    result = (values / norms).astype(np.float32)
    if result.shape[-1] != DIMENSION or not np.all(np.isfinite(result)):
        raise ValueError("generated vectors must be finite and 384-dimensional")
    return result


def generate_batches(
        chunk_count: int,
        seed: int = SEED,
        batch_size: int = 2_000,
) -> tuple[Iterator[GeneratedBatch], list[np.ndarray]]:
    if chunk_count < 1 or batch_size < 1:
        raise ValueError("chunk_count and batch_size must be positive")
    query_centroids: list[np.ndarray] = []

    def iterator() -> Iterator[GeneratedBatch]:
        rng = np.random.default_rng(seed)
        query_rng = np.random.default_rng(seed + 1)
        reservoir: list[np.ndarray] = []
        seen_documents = 0
        document_id = 1
        generated = 0
        ids: list[int] = []
        indexes: list[int] = []
        vectors: list[np.ndarray] = []
        while generated < chunk_count:
            chunks_for_document = min(int(rng.geometric(0.20)), 20, chunk_count - generated)
            centroid = normalized(rng.normal(size=(1, DIMENSION)).astype(np.float32))[0]
            document_vectors = normalized(
                centroid + rng.normal(0.0, 0.075, size=(chunks_for_document, DIMENSION)).astype(np.float32)
            )
            seen_documents += 1
            if len(reservoir) < 110:
                reservoir.append(centroid.copy())
            else:
                replacement = int(query_rng.integers(0, seen_documents))
                if replacement < len(reservoir):
                    reservoir[replacement] = centroid.copy()
            for chunk_index, vector in enumerate(document_vectors):
                ids.append(document_id)
                indexes.append(chunk_index)
                vectors.append(vector)
                generated += 1
                if len(ids) == batch_size:
                    yield GeneratedBatch(
                        np.asarray(ids, dtype=np.int64),
                        np.asarray(indexes, dtype=np.int16),
                        np.asarray(vectors, dtype=np.float32),
                    )
                    ids, indexes, vectors = [], [], []
            document_id += 1
        if ids:
            yield GeneratedBatch(
                np.asarray(ids, dtype=np.int64),
                np.asarray(indexes, dtype=np.int16),
                np.asarray(vectors, dtype=np.float32),
            )
        query_centroids.extend(reservoir)

    return iterator(), query_centroids


def generated_vector_checksum(chunk_count: int, seed: int = SEED) -> str:
    batches, _ = generate_batches(chunk_count, seed, batch_size=37)
    digest = hashlib.sha256()
    for batch in batches:
        digest.update(batch.document_ids.tobytes())
        digest.update(batch.chunk_indexes.tobytes())
        digest.update(batch.vectors.tobytes())
    return digest.hexdigest()


def create_table(connection: psycopg.Connection) -> None:
    with connection.cursor() as cursor:
        cursor.execute(f"DROP TABLE IF EXISTS {TABLE}")
        cursor.execute(f"""
            CREATE TABLE {TABLE} (
                document_id bigint NOT NULL,
                chunk_index smallint NOT NULL,
                embedding vector({DIMENSION}) NOT NULL
            )
        """)
    connection.commit()


def load_vectors(
        connection: psycopg.Connection,
        chunk_count: int,
        batch_size: int,
) -> tuple[float, list[np.ndarray], int]:
    batches, query_centroids = generate_batches(chunk_count, SEED, batch_size)
    started = time.perf_counter()
    loaded = 0
    with connection.cursor() as cursor:
        with cursor.copy(f"COPY {TABLE} (document_id, chunk_index, embedding) FROM STDIN (FORMAT BINARY)") as copy:
            copy.set_types(["int8", "int2", "vector"])
            for batch in batches:
                for document_id, chunk_index, vector in zip(
                        batch.document_ids, batch.chunk_indexes, batch.vectors, strict=True
                ):
                    copy.write_row((int(document_id), int(chunk_index), vector))
                    loaded += 1
    connection.commit()
    if loaded != chunk_count or len(query_centroids) < 100:
        raise RuntimeError("deterministic generator did not produce the requested scale/query set")
    return time.perf_counter() - started, query_centroids, loaded


def build_index(connection: psycopg.Connection) -> float:
    with connection.cursor() as cursor:
        cursor.execute(f"ANALYZE {TABLE}")
        started = time.perf_counter()
        cursor.execute(f"CREATE INDEX {INDEX} ON {TABLE} USING hnsw (embedding vector_cosine_ops)")
    connection.commit()
    elapsed = time.perf_counter() - started
    with connection.cursor() as cursor:
        cursor.execute(f"ANALYZE {TABLE}")
    connection.commit()
    return elapsed


def prepare_queries(centroids: Sequence[np.ndarray], count: int = 110) -> list[np.ndarray]:
    rng = np.random.default_rng(SEED + 2)
    selected = np.asarray(centroids[:count], dtype=np.float32)
    return list(normalized(selected + rng.normal(0.0, 0.035, selected.shape).astype(np.float32)))


def retrieve(
        connection: psycopg.Connection,
        query: np.ndarray,
        chunk_limit: int,
        exact: bool,
        ef_search: int,
) -> Retrieval:
    with connection.transaction():
        with connection.cursor() as cursor:
            if exact:
                cursor.execute("SET LOCAL enable_indexscan = off")
                cursor.execute("SET LOCAL enable_indexonlyscan = off")
                cursor.execute("SET LOCAL enable_bitmapscan = off")
            else:
                cursor.execute("SELECT set_config('hnsw.ef_search', %s, true)", (str(ef_search),))
            started = time.perf_counter()
            cursor.execute(f"""
                SELECT document_id, chunk_index,
                       1 - (embedding <=> %s::vector) AS similarity
                FROM {TABLE}
                ORDER BY embedding <=> %s::vector
                LIMIT %s
            """, (query, query, chunk_limit))
            rows = cursor.fetchall()
            elapsed_ms = (time.perf_counter() - started) * 1000
    best_by_document: dict[int, float] = {}
    for document_id, _, similarity in rows:
        best_by_document[int(document_id)] = max(float(similarity), best_by_document.get(int(document_id), -math.inf))
    document_hits = tuple(
        document_id for document_id, _ in sorted(best_by_document.items(), key=lambda item: (-item[1], item[0]))[:50]
    )
    return Retrieval(
        tuple((int(document_id), int(chunk_index)) for document_id, chunk_index, _ in rows),
        document_hits,
        len(best_by_document),
        elapsed_ms,
    )


def evaluate_configuration(
        connection: psycopg.Connection,
        queries: Sequence[np.ndarray],
        chunk_limit: int,
        ef_search: int,
) -> ConfigurationResult:
    exact_results = [retrieve(connection, query, chunk_limit, True, ef_search) for query in queries]
    approximate_results = [retrieve(connection, query, chunk_limit, False, ef_search) for query in queries]
    chunk_recalls: list[float] = []
    document_recalls: list[float] = []
    for exact, approximate in zip(exact_results, approximate_results, strict=True):
        exact_chunks = set(exact.chunk_hits)
        chunk_recalls.append(len(exact_chunks & set(approximate.chunk_hits)) / len(exact_chunks))
        exact_documents = set(exact.document_hits)
        document_recalls.append(
            len(exact_documents & set(approximate.document_hits)) / len(exact_documents)
            if exact_documents else 1.0
        )
    exact_latencies = [result.latency_ms for result in exact_results]
    hnsw_latencies = [result.latency_ms for result in approximate_results]
    return ConfigurationResult(
        chunk_limit,
        ef_search,
        mean(chunk_recalls),
        mean(document_recalls),
        mean(result.distinct_documents for result in approximate_results),
        percentile(exact_latencies, 0.50),
        percentile(exact_latencies, 0.95),
        percentile(hnsw_latencies, 0.50),
        percentile(hnsw_latencies, 0.95),
        percentile(hnsw_latencies, 0.99),
    )


def explain(connection: psycopg.Connection, query: np.ndarray, exact: bool) -> object:
    with connection.transaction():
        with connection.cursor() as cursor:
            if exact:
                cursor.execute("SET LOCAL enable_indexscan = off")
                cursor.execute("SET LOCAL enable_indexonlyscan = off")
                cursor.execute("SET LOCAL enable_bitmapscan = off")
            else:
                cursor.execute("SELECT set_config('hnsw.ef_search', '500', true)")
            cursor.execute(f"""
                EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
                SELECT document_id, chunk_index
                FROM {TABLE}
                ORDER BY embedding <=> %s::vector
                LIMIT 250
            """, (query,))
            return cursor.fetchone()[0]


def relation_size(connection: psycopg.Connection, relation: str, table_only: bool = False) -> int:
    with connection.cursor() as cursor:
        function = "pg_table_size" if table_only else "pg_total_relation_size"
        cursor.execute(f"SELECT {function}(%s::regclass)", (relation,))
        return int(cursor.fetchone()[0])


def percentile(values: Sequence[float], fraction: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def run(args: argparse.Namespace) -> None:
    if args.measured_queries < 100 or args.warmup_queries < 10:
        raise ValueError("scale evaluation requires at least 10 warmups and 100 measured queries")
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with psycopg.connect(args.dsn, autocommit=False) as connection:
        register_vector(connection)
        create_table(connection)
        load_seconds, centroids, loaded = load_vectors(connection, args.chunks, args.batch_size)
        build_seconds = build_index(connection)
        queries = prepare_queries(centroids, args.warmup_queries + args.measured_queries)
        for query in queries[:args.warmup_queries]:
            retrieve(connection, query, 250, False, 500)
        measured = queries[args.warmup_queries:]
        results = [evaluate_configuration(connection, measured, 250, 500)]
        if results[0].document_recall_at_50 < 0.98:
            for chunk_limit, ef_search in ((250, 750), (500, 750), (500, 1000)):
                results.append(evaluate_configuration(connection, measured, chunk_limit, ef_search))
                if results[-1].document_recall_at_50 >= 0.98:
                    break
        plans = {"exact": explain(connection, measured[0], True), "hnsw": explain(connection, measured[0], False)}
        table_bytes = relation_size(connection, TABLE, table_only=True)
        index_bytes = relation_size(connection, INDEX)
        with connection.cursor() as cursor:
            cursor.execute("SELECT version(), extversion FROM pg_extension WHERE extname = 'vector'")
            postgres_version, pgvector_version = cursor.fetchone()
        payload = {
            "runAt": datetime.now(UTC).isoformat(),
            "seed": SEED,
            "dimension": DIMENSION,
            "chunkCount": loaded,
            "distribution": "document centroids with 1-20 chunks/document, geometric p=0.20, noise sigma=0.075",
            "postgresVersion": postgres_version,
            "pgvectorVersion": pgvector_version,
            "loadSeconds": load_seconds,
            "buildSeconds": build_seconds,
            "tableBytes": table_bytes,
            "indexBytes": index_bytes,
            "warmupQueries": args.warmup_queries,
            "measuredQueries": args.measured_queries,
            "configurations": [asdict(result) for result in results],
            "plans": plans,
        }
        output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        if args.drop_after:
            with connection.cursor() as cursor:
                cursor.execute(f"DROP TABLE {TABLE}")
            connection.commit()
    print(json.dumps({key: value for key, value in payload.items() if key != "plans"}, indent=2))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark exact and HNSW pgvector retrieval at deterministic scale")
    parser.add_argument("--dsn", default="postgresql://nevis:nevis@127.0.0.1:15432/nevis")
    parser.add_argument("--chunks", type=int, choices=(100_000, 500_000, 1_000_000), required=True)
    parser.add_argument("--batch-size", type=int, default=2_000)
    parser.add_argument("--warmup-queries", type=int, default=10)
    parser.add_argument("--measured-queries", type=int, default=100)
    parser.add_argument("--drop-after", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    try:
        run(parse_args())
    except Exception as error:
        print(f"Scale evaluation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
