# MCP servers

| Server | Language | Tools | Status |
|---|---|---|---|
| `ledger-mcp` | Java, MCP Java SDK 2.2.0 | `search_transactions`, `get_transaction`, `get_cardholder`, `get_card`, `get_merchant`, `get_authorisations` | M1: answers from PostgreSQL as a read-only role |
| `policy-mcp` | Python, `mcp` 2.3.0, FastAPI | `search_policy`, `get_policy_section` | M1: hybrid retrieval over the corpus in PostgreSQL |
| `actions-mcp` | Java, MCP Java SDK 2.2.0 | `issue_provisional_credit`, `request_merchant_evidence`, `close_dispute` (each requires `approval_token`) | Verifies Ed25519 approval tokens itself and carries each approved action out once, within the business limits (task 2.6) |

Transport: Streamable HTTP. Each server has a contract test that pins the `tools/list` output.
