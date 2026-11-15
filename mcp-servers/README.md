# MCP servers

| Server | Language | Tools | Status |
|---|---|---|---|
| `ledger-mcp` | Java, MCP Java SDK 2.1.0 | `search_transactions`, `get_transaction`, `get_cardholder`, `get_card`, `get_merchant`, `get_authorisations` | M0: `tools/list` done, stub handlers |
| `policy-mcp` | Python, `mcp` 2.3.0, FastAPI | `search_policy`, `get_policy_section` | M0: `tools/list` |
| `actions-mcp` | Java, MCP Java SDK 2.1.0 | `issue_provisional_credit`, `request_merchant_evidence`, `close_dispute` (each requires `approval_token`) | M0: `tools/list` |

Transport: Streamable HTTP. Each server has a contract test that pins the `tools/list` output.
