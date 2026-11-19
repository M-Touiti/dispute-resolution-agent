# policy-mcp

Read-only MCP server over the card dispute policy corpus (`data/policy-corpus/`). Python 3.13, `mcp` 2.3.0,
FastAPI 0.163.1, uv. The SDK's low-level `Server` serves explicit tool definitions over Streamable HTTP at `/mcp`,
mounted in a FastAPI app that also serves `/health`. Port 8082.

| Tool | Required input | Purpose |
|---|---|---|
| `search_policy` | `query` | Sections relevant to a question, best match first; optional `category` filter and `max_results` (1 to 10, default 5) |
| `get_policy_section` | `section_id` | Full text of one section, with title, version, categories and source file |

## Behaviour

- **Ingestion.** At start-up the corpus is parsed and stored in the `policy` schema of PostgreSQL: one row per
  section with a weighted full-text vector (title above body) and an embedding. It runs only when the corpus
  fingerprint or the embedding model changes, under an advisory lock, in one transaction. The server accepts
  requests only after it.
- **Hybrid retrieval** (ADR 0007): PostgreSQL full text (any query word) and pgvector cosine similarity each give
  20 candidates, merged by reciprocal-rank fusion with full text at half weight. Ranks only, ties by section id:
  the same corpus, model and query give the same bytes. A `category` keeps that category's sections and the
  general ones. Each hit says whether it came from `full_text`, `semantic` or both.
- **Embeddings** (ADR 0004, model per ADR 0007): `BAAI/bge-small-en-v1.5` on ONNX Runtime through `fastembed`,
  behind an `Embedder` port. The image bakes the model in, checks its SHA-256 and runs offline. Tests use a
  deterministic hash embedder, except the retrieval-quality test, which uses the real model.
- **Least privilege.** The server connects as `policy_service`, which owns the `policy` schema and has no access
  to `ledger` or `intake` (`deploy/compose/postgres/initdb/30-policy-service.sh`).
- **Errors** come back as `isError: true`: `INVALID_INPUT` (does not match the schema), `INVALID_ARGUMENT`
  (`max_results` out of range, empty query, malformed section id), `NOT_FOUND`, `UNAVAILABLE` (database failure;
  details only in the log).
- DNS rebinding protection is on: only the Host headers in `POLICY_MCP_ALLOWED_HOSTS` are served.

Configuration: `POLICY_DB_URL` (default `postgresql://policy_service:policy_service@localhost:5432/disputes`),
`POLICY_CORPUS_DIR` (default `data/policy-corpus` of the checkout), `POLICY_EMBEDDER` (`fastembed`; `hash` is for
tests), `POLICY_MCP_ALLOWED_HOSTS`, `FASTEMBED_CACHE_PATH`.

## Run and test

The tests need Docker: they start the compose PostgreSQL image with its init scripts (Testcontainers).

```sh
uv sync
uv run uvicorn --factory policy_mcp.app:create_app --port 8082   # needs PostgreSQL, e.g. the compose stack
uv run pytest                                                     # all tests, including retrieval quality
uv run pytest tests/test_tools_list_contract.py                   # the contract test only
uv run ruff check . && uv run ruff format --check .
POLICY_QUALITY_REPORT=1 uv run --with model2vec==0.10.0 pytest -s tests/test_retrieval_quality.py  # comparison
```

The contract test connects with the SDK's `Client` over the legacy initialize handshake (protocol 2025-11-25, what
the investigator's Java client speaks) and compares a key-sorted `tools/list` with
`tests/contract/tools-list.json`; it also checks that the 2026-07-28 protocol lists the same tools. For an intended
change to a tool, regenerate and review the diff:

```sh
CONTRACT_UPDATE=1 uv run pytest tests/test_tools_list_contract.py
```

A change to a tool, the corpus, the model or the fusion re-records the investigator's fixtures in the same pull
request; a change that moves retrieval quality revises the floors in `test_retrieval_quality.py` with the new
numbers.
