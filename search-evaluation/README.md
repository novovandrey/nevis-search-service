# Nevis search evaluation

This module evaluates the real Java retrieval implementation. It never reimplements PostgreSQL FTS,
embedding, or RRF in Python.

## Run

Start a dedicated, empty evaluation instance on API port `18080` and loopback-only database port
`15432`. The reset script removes only the `nevis-evaluation` Compose project and its volume:

```bash
./search-evaluation/reset-evaluation-stack.sh MINILM 240 30
```

The project name is deliberate: it keeps the evaluation database separate from a normal local
instance. Before a new benchmark run, stop that project and remove *only* its named volume:

```bash
docker compose -f compose.yaml -f search-evaluation/compose.evaluation.yaml \
  -p nevis-evaluation down --volumes
```

Then run the benchmark once the API is ready:

```bash
cd search-evaluation
python3 quality_runner.py --base-url http://127.0.0.1:18080 --ann-grid \
  --output output/minilm-240-tuning
```

Allowed model values are `MINILM`, `BGE_SMALL_EN_V15`, and `E5_SMALL_V2`. Only the selected
full-precision model is instantiated by the evaluation Spring profile. Production beans and the
public REST API remain unchanged.

For deterministic scale measurements, install the declared Python dependencies and run, for example:

```bash
python3 -m venv .venv
.venv/bin/pip install -e .
.venv/bin/python scale_runner.py \
  --chunks 100000 --output output/scale-100k.json
```

The scale runner creates and drops `evaluation_scale_chunks`; it never alters Flyway migrations or
the production document tables. Pass `NEVIS_TEST_DSN=postgresql://nevis:nevis@127.0.0.1:15432/nevis`
when running Python tests against the isolated stack to execute the binary-COPY smoke test.

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
