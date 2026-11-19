# ledger-mcp

Read-only MCP server over the synthetic card ledger. Java 21, Spring Boot 4.1.2, MCP Java SDK 2.2.0 (`mcp-core` +
`mcp-json-jackson3`), no Spring AI: the SDK's `HttpServletStreamableServerTransportProvider` is registered at `/mcp`
through a `ServletRegistrationBean`.

| Tool | Required input | Purpose |
|---|---|---|
| `search_transactions` | `card_id` | Purchases and refunds on a card; date, merchant and kind filters; paginated |
| `get_transaction` | `transaction_id` | One transaction with its authorisation id and refund link |
| `get_cardholder` | `cardholder_id` | Profile: name, home city, customer since |
| `get_card` | `card_id` | Product, status, expiry, last four digits (never a full number) |
| `get_merchant` | `merchant_id` | Category, location, descriptor, online and recurring flags |
| `get_authorisations` | `card_id` | Attempts including declines: entry mode, 3-D Secure, CVV, PIN, fraud flag; paginated |

All tools are annotated read-only and idempotent. Input schemas are in `src/main/resources/tools/` and stay inside
the subset that strict tool use accepts (no numeric or length constraints, no `pattern`,
`additionalProperties: false`); `ToolSchemaRulesTest` enforces this. The server also validates inputs against the
schemas.

## Behaviour

- **Data:** the `ledger` schema loaded from `data/generated/seed.sql`, read through Spring's `JdbcClient`.
- **Least privilege:** the server connects as `ledger_reader`, a role with `SELECT` on the `ledger` schema only
  (no `intake`, where disputes live; no writes; read-only transactions by default), created by
  `deploy/compose/postgres/initdb/20-ledger-reader.sh`. The connection pool is read-only as well.
- **Results** are JSON text: snake_case fields, amounts in minor units with their currency, UTC timestamps,
  ISO dates. A card is returned with its last four digits only; a cardholder without the e-mail address.
- **Lists** (`search_transactions`, `get_authorisations`) are newest first with the id as tie-breaker, 20 per page
  by default and at most 50, as `{"items": [...], "next_page_token": ...}`. The token is an opaque keyset cursor
  bound to the tool that issued it; `next_page_token` is null on the last page. Date filters are inclusive, in UTC.
- **Errors** come back as `isError: true` with a code the model can act on: `INVALID_ARGUMENT` (malformed id,
  page size outside 1 to 50, `from_date` after `to_date`, a foreign or corrupt page token), `NOT_FOUND`, or
  `UNAVAILABLE` (database failure; details go to the log, never to the client). The id formats and page-size
  range are checked here because strict tool schemas cannot express them.

Configuration: `LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD` (defaults: `localhost:5432/disputes`,
`ledger_reader`). Readiness (`/actuator/health/readiness`) includes the database.

## Run and test

The database tests need Docker and the generated seed (`uv run python generate.py` in `data/`); without the seed
they fail rather than skip. `LedgerToolsDatabaseTest` starts the compose PostgreSQL image with the compose init
scripts, so the role and the data are the same as in the stack, and takes its expected values from the generated
CSV files.

```sh
./mvnw verify                                   # build and all tests (mvnw.cmd on Windows)
./mvnw test -Dtest=ToolsListContractTest        # the contract test only
./mvnw spring-boot:run                          # serves http://localhost:8081/mcp; health at /actuator/health
```

`ToolsListContractTest` starts the server on a random port, connects with the SDK's
`HttpClientStreamableHttpTransport` (the investigator's client transport), and compares a key-sorted `tools/list`
with `src/test/resources/contract/tools-list.json`. For an intended change to a tool, regenerate and review the diff:

```sh
./mvnw test -Dtest=ToolsListContractTest -Dcontract.update=true
```

A tool change also changes the investigator's prompt, so its fixtures are re-recorded in the same pull request.
