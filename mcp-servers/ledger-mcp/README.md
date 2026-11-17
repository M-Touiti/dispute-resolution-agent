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

**M0 status:** the handlers are stubs that return `isError: true` with `NOT_IMPLEMENTED`. M1 backs them with the
`ledger` schema from `data/generated/seed.sql`.

## Run and test

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
