from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

from dataset import load_dataset
from quality_runner import ApiClient, create_corpus, dataset_checksum


def run(args: argparse.Namespace) -> None:
    dataset = load_dataset(args.dataset)
    client = ApiClient(args.base_url, args.timeout)
    startup_metadata = client.get("/internal/evaluation/metadata")
    runtime_ids, indexing_seconds = create_corpus(client, dataset)
    populated_metadata = client.get("/internal/evaluation/metadata")
    payload = {
        "loadedAt": datetime.now(UTC).isoformat(),
        "dataset": str(args.dataset.resolve()),
        "datasetSha256": dataset_checksum(args.dataset),
        "startupMetadata": startup_metadata,
        "populatedMetadata": populated_metadata,
        "indexingSeconds": indexing_seconds,
        "runtimeDocumentIds": runtime_ids,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output.resolve()),
        "documentCount": populated_metadata["database"]["documentCount"],
        "chunkCount": populated_metadata["database"]["chunkCount"],
        "model": populated_metadata["model"]["modelId"],
        "minimumSimilarity": populated_metadata["semantic"]["minimumSimilarity"],
    }, indent=2))


def parse_args() -> argparse.Namespace:
    base = Path(__file__).parent
    parser = argparse.ArgumentParser(description="Load the deterministic corpus without consuming query holdout")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--dataset", type=Path, default=base / "data" / "long-document-dataset.json")
    parser.add_argument("--output", type=Path, default=base / "output" / "loaded-corpus.json")
    parser.add_argument("--timeout", type=float, default=180.0)
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
