# actions-mcp

The only MCP server that changes state. Java 21, Spring Boot 4.2.0, MCP Java SDK 2.2.0 (`mcp-core` +
`mcp-json-jackson3`), no Spring AI: the SDK's `HttpServletStreamableServerTransportProvider` is registered at `/mcp`
through a `ServletRegistrationBean`. Port 8083.

| Tool | Required input | Effect |
|---|---|---|
| `close_dispute` | `dispute_id`, `reason`, `policy_section_ids`, `approval_token` | Closes the dispute as declined and notifies the cardholder (destructive) |
| `issue_provisional_credit` | `dispute_id`, `amount_minor`, `currency`, `approval_token` | Credits the cardholder provisionally, at most the disputed amount |
| `request_merchant_evidence` | `dispute_id`, `evidence`, `note`, `approval_token` | Asks the merchant for receipts, proof of delivery and similar evidence |

Every tool requires an `approval_token`: a signed, single-use token from the human approval gate, bound to one
dispute, one tool and the exact arguments. This server verifies the token itself and does not trust the client
(ADR 0005, M2). Business limits such as "credit at most the disputed amount" will be checked here as well.

Input schemas are in `src/main/resources/tools/` and stay inside the subset that strict tool use accepts;
`ToolSchemaRulesTest` enforces this and that every tool requires `dispute_id` and `approval_token`. The server
also validates inputs against the schemas, so a call without a token never reaches a handler.

**M0 status:** token verification does not exist yet, so every handler rejects every call with `isError: true`
and `APPROVAL_REJECTED`, and nothing changes. M2 adds verification and the writes.

## Run and test

```sh
./mvnw verify                                   # build and all tests (mvnw.cmd on Windows)
./mvnw test -Dtest=ToolsListContractTest        # the contract test only
./mvnw spring-boot:run                          # serves http://localhost:8083/mcp; health at /actuator/health
```

`ToolsListContractTest` starts the server on a random port, connects with the SDK's
`HttpClientStreamableHttpTransport` (the investigator's client transport), and compares a key-sorted `tools/list`
with `src/test/resources/contract/tools-list.json`. For an intended change to a tool, regenerate and review the diff:

```sh
./mvnw test -Dtest=ToolsListContractTest -Dcontract.update=true
```

A tool change also changes the investigator's prompt, so its fixtures are re-recorded in the same pull request.
