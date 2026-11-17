# policy-mcp

Read-only MCP server over the card dispute policy corpus. Python 3.13, `mcp` 2.3.0, FastAPI 0.163.1, uv. The
SDK's low-level `Server` serves explicit tool definitions over Streamable HTTP at `/mcp`, mounted in a FastAPI app
that also serves `/health`. Port 8082.

| Tool | Required input | Purpose |
|---|---|---|
| `search_policy` | `query` | Sections relevant to a question, best match first; optional `category` filter and `max_results` |
| `get_policy_section` | `section_id` | Full text of one section, with title, version and categories |

Both tools are annotated read-only and idempotent. Input schemas are in `policy_mcp/tools/` and stay inside the
subset that strict tool use accepts; `tests/test_tool_schema_rules.py` enforces this and checks that the `category`
enum matches the data generator. The low-level server does not validate inputs, so `policy_mcp/server.py` checks
every call against its schema with `jsonschema` and answers `INVALID_INPUT: ...` with `isError: true`.

DNS rebinding protection is on: only the Host headers in `POLICY_MCP_ALLOWED_HOSTS` (comma-separated, default
`127.0.0.1:*,localhost:*,[::1]:*`) are served. Compose adds the service name.

**M0 status:** the handlers are stubs that return `isError: true` with `NOT_IMPLEMENTED`. M1 adds the policy corpus
(`data/policy-corpus/`) and hybrid retrieval: PostgreSQL full text plus pgvector with a local embedding model
(ADR 0004).

## Run and test

```sh
uv sync
uv run uvicorn policy_mcp.app:app --port 8082     # serves http://localhost:8082/mcp; health at /health
uv run pytest                                      # all tests
uv run pytest tests/test_tools_list_contract.py   # the contract test only
uv run ruff check . && uv run ruff format --check .
```

The contract test starts the app under uvicorn on a free port, connects with the SDK's `Client` over the legacy
initialize handshake (protocol 2025-11-25, what the investigator's Java client speaks), and compares a key-sorted
`tools/list` with `tests/contract/tools-list.json`. It also checks that the 2026-07-28 protocol lists the same
tools. For an intended change to a tool, regenerate and review the diff:

```sh
CONTRACT_UPDATE=1 uv run pytest tests/test_tools_list_contract.py
```

A tool change also changes the investigator's prompt, so its fixtures are re-recorded in the same pull request.
