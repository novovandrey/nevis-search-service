#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "usage: $0 MODEL MAX_INPUT_TOKENS OVERLAP_TOKENS [MINIMUM_SIMILARITY]" >&2
  exit 2
fi

evaluation_model="$1"
max_input_tokens="$2"
overlap_tokens="$3"
minimum_similarity="${4:-0.30}"
project="nevis-evaluation"
compose_files=(-f compose.yaml -f search-evaluation/compose.evaluation.yaml)

export APP_HOST_PORT=18080
export EVALUATION_DB_HOST_PORT=15432
export EVALUATION_EMBEDDING_MODEL="$evaluation_model"
export DOCUMENT_CHUNK_MAX_INPUT_TOKENS="$max_input_tokens"
export DOCUMENT_CHUNK_MAX_TITLE_TOKENS=32
export DOCUMENT_CHUNK_OVERLAP_TOKENS="$overlap_tokens"
export SEMANTIC_CANDIDATE_LIMIT=50
export SEMANTIC_CHUNK_CANDIDATE_LIMIT=250
export SEMANTIC_HNSW_EF_SEARCH=500
export SEMANTIC_MINIMUM_SIMILARITY="$minimum_similarity"

docker compose "${compose_files[@]}" -p "$project" down --volumes --remove-orphans
docker compose "${compose_files[@]}" -p "$project" up -d --build

for attempt in $(seq 1 90); do
  if curl --fail --silent --show-error http://127.0.0.1:18080/v3/api-docs >/dev/null; then
    curl --fail --silent --show-error http://127.0.0.1:18080/internal/evaluation/metadata
    echo
    exit 0
  fi
  sleep 2
done

docker compose "${compose_files[@]}" -p "$project" ps
docker compose "${compose_files[@]}" -p "$project" logs --tail=200 app
echo "evaluation API did not become ready" >&2
exit 1
