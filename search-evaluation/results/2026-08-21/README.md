# 2026-08-21 evaluation evidence

These compact aggregate files were copied from the isolated mini-PC evaluation worktree. Raw HTTP
responses remain in ignored `search-evaluation/output/<run-id>` directories on that host. Every run
used PostgreSQL 17.8, pgvector 0.8.1, the long-document corpus SHA-256
`8ebcbf4fb50dc7f5131d897e76ddb54a93bbd07e55a03aaa1d0365c45ddb1824`, document/chunk/HNSW limits
`50/250/500`, and RRF `60/1.25/1.0`.

Tuning runs used evaluation commit `f052f7a`. Fixed-threshold holdout runs used `5c12009`; the
holdout runner recorded `thresholdSelection=fixed-holdout` and evaluated no threshold candidates.
The JSON `runAt` field is the authoritative UTC timestamp. Mini-PC environment details are recorded
in the repository reports.

| Aggregate | Split | Model | Chunk config | SHA-256 |
|---|---|---|---|---|
| `minilm-160-tuning.json` | tuning | MiniLM | 160/32/20 | `b9ea5a348e10aa343c63a5fa77addfdb29d87ed7c6f57caa832af2bcb56cceae` |
| `minilm-160-ann-tuning.json` | tuning | MiniLM | 160/32/20 | `98e2fa5c6c0fde8e4024fa155df34d1bdb4e5e825b97282c0620d33cc0e22907` |
| `minilm-200-tuning.json` | tuning | MiniLM | 200/32/30 | `1b97dc89f58d89652a5d9f5cb891a533002db5d7a4ce5baee595194d79e66ef3` |
| `minilm-240-tuning.json` | tuning | MiniLM | 240/32/30 | `5b9b334f4147ae40d02f0095deea27f6f0d42f9afd26f2f151ea7fc84e41a71f` |
| `bge-240-ann-tuning.json` | tuning | BGE small en v1.5 | 240/32/30 | `aacc4bbf3343a154c22c610d7d27e97ad2671af844f689c782edbdfc92c7f2cd` |
| `bge-360-tuning.json` | tuning | BGE small en v1.5 | 360/32/40 | `5a25d7bd46f4e82edd49710793bbc37d1a13fa42a7f565d3ecf60f8f26c6659a` |
| `bge-480-tuning.json` | tuning | BGE small en v1.5 | 480/32/50 | `df2af8946ca1d4c7298c112392933b27da0e4a0049fbdf9c2543f0cc4004f2ad` |
| `e5-240-ann-tuning.json` | tuning | E5 small v2 | 240/32/30 | `a2a679d202986e34650f37a1c9bf7fcceb399fa9f9ce722b74eb808a6fdc1b58` |
| `e5-360-tuning.json` | tuning | E5 small v2 | 360/32/40 | `082ee413dfe501a58386e121f7f18389395b1f8229b9cfafb689f32847159d92` |
| `e5-480-tuning.json` | tuning | E5 small v2 | 480/32/50 | `411e8a217c13f7f798a8551fccf0aba5b23c9736ec2b36626e2ff271d02f7d7d` |
| `minilm-160-holdout.json` | holdout | MiniLM | 160/32/20 | `9e264fa6d308c9cc39b65bf357068b4a2a37366356af9ba10d6d26afcf4d894e` |
| `bge-240-holdout.json` | holdout | BGE small en v1.5 | 240/32/30 | `c8b596c4753966e7e3517d5fd978705137078edcc2316e29398329e5a9efbecf` |
| `e5-240-holdout.json` | holdout | E5 small v2 | 240/32/30 | `9085f635718de7c48e97ebad68879c37dd71692d43cbbdefcc85d1eeb190707a` |

Scale runs used commit `f052f7a`, seed `20260821`, synthetic finite normalized 384-dimensional
vectors, 10 warmup and 100 measured queries. They are not tied to an embedding model or quality
split. The 1M run was cancelled by owner decision and produced no evidence file.

| Aggregate | Scale | SHA-256 |
|---|---:|---|
| `scale-100k.json` | 100,000 chunks | `d5403adde7968dec7681cdd307bdb2d5e98cf80ba0de820e5ee625d5e13e944f` |
| `scale-500k.json` | 500,000 chunks | `e50f1b732ace72e1d9ea009a6b46cbb1b4a9437cc673c41dbe839c2a6e93c2e5` |
