# Nevis search evaluation

This module evaluates the real Java retrieval implementation. It never reimplements PostgreSQL FTS,
embedding, or RRF in Python.

## Run

Start a dedicated, empty evaluation instance of the service:

```bash
SPRING_PROFILES_ACTIVE=evaluation docker compose -p nevis-evaluation up --build
```

The project name is deliberate: it keeps the evaluation database separate from a normal local
instance. Before a new benchmark run, stop that project and remove *only* its named volume:

```bash
docker compose -p nevis-evaluation down --volumes
```

Then run the benchmark once the API is ready:

```bash
cd search-evaluation
python3 evaluate.py --base-url http://localhost:8080
```

The runner creates a fresh synthetic client and document for every benchmark document through the
ordinary public API. Do not point it at shared or production data: the corpus is additive and the
service intentionally has no delete API.

## Outputs

`output/latest/` contains the runtime document-id mapping, raw Java responses, metric tables,
per-query diagnostics (top semantic scores, score gap, candidate counts and agreement),
hard-negative mining, recommendations, and SVG charts. It is ignored by Git because each run is
machine- and corpus-model-specific. Commit the reviewed result tables and conclusions into the
repository-level evaluation documents instead.

The runner first records LEXICAL, SEMANTIC and HYBRID baselines, then tests the fixed threshold
grid and offline rejection policies. Policy cut-points are derived from tuning scores and gaps.
Python only filters Java-produced final rankings; it never recreates FTS, vector retrieval or RRF.

## Tests

```bash
cd search-evaluation
python3 -m unittest discover -s tests -v
```
