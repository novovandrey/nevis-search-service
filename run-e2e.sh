#!/usr/bin/env bash
set -euo pipefail

cleanup() {
  docker compose down -v
}

trap cleanup EXIT

docker compose down -v
docker compose up -d --build

python3 e2e/blackbox_api_tests.py --base-url http://localhost:8080
