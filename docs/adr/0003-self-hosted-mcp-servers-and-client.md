# ADR 0003: Self-hosted MCP servers and client versus the Messages API MCP connector

- Status: accepted
- Date: 2026-11-19
- Deciders: repository owner

## Context

The agent reaches the ledger, the policy corpus and the dispute actions through three MCP servers (`ledger-mcp`,
`policy-mcp`, `actions-mcp`), which exist since M0. Something must connect Claude to them.

The Messages API offers an MCP connector (beta `mcp-client-2025-11-20`): the request lists servers by URL in
`mcp_servers` and references each from an `mcp_toolset` entry in `tools`; Anthropic connects to the servers and
runs the tool calls server-side, inside its own sampling loop. A toolset can allowlist tools through per-tool
configuration.

Facts that decide the question here:

1. **The servers are private.** They run beside PostgreSQL on a compose network or in a cluster, with no public
   endpoint. The connector reaches servers from Anthropic's side, so it would need them exposed to the internet
   with authentication.
2. **Every tool call must pass through the investigator** before it runs: argument validation against the strict
   schema, the per-server allowlist (no write server without an approval token in hand), the per-case caps, and an
   audit record. With the connector the call happens inside Anthropic's loop; the investigator sees it only after
   the fact, in the response.
3. **Record and replay** (ADR 0001, ADR 0006) key fixtures on the request. With the connector, tool results are
   produced inside the model call and are not inputs we control, so a replayed run could not exercise our tool
   servers, and a change in the ledger data would not show up as a fixture miss.
4. **Writing the client is part of the point** of the repository: discovery, schema conversion, routing,
   timeouts.

For the Java side there is a second choice: the raw MCP Java SDK or Spring AI, which wraps it. In SDK 2.x the
Spring WebMVC server transport and the Spring client integration live in Spring AI; the plain `mcp-core` artifact
has a servlet server transport and a JDK `HttpClient` client transport.

## Decision

- **Self-hosted servers, our own client.** The investigator's `mcp-client` adapter connects to each server over
  Streamable HTTP at start-up, discovers the tools with `tools/list`, converts each input schema into a strict
  tool definition for the Messages API, and routes every `tool_use` block to its server by tool name. The tool
  list given to Claude is sorted by name and stable across calls (prompt caching, fixture keys).
- **Allowlist per server.** In M1 the client connects only to `ledger-mcp` and `policy-mcp`. From M2 the
  investigator calls `actions-mcp` itself after approval; Claude proposes actions in its decision and never
  receives the write tools.
- **Raw MCP Java SDK 2.x, no Spring AI**, on both sides: servers register `HttpServletStreamableServerTransportProvider`
  through a `ServletRegistrationBean`; the client uses `HttpClientStreamableHttpTransport`. `policy-mcp` uses the
  official Python SDK's low-level `Server`.
- **Schemas are a contract.** Each server pins its `tools/list` output in a contract test; a schema change
  re-records fixtures in the same pull request (ADR 0001).

## Consequences

- No server is exposed outside the private network; no MCP credential leaves the deployment.
- The investigator decides, before execution, whether a call runs, and records it. The approval gate and the
  allowlist cannot be bypassed by the model.
- Replay exercises the real client and the real servers against the seeded ledger, so CI tests the protocol path
  as well as the loop.
- We maintain the client: reconnects, timeouts, schema conversion, and SDK upgrades (the contract tests gate
  those). Tool calls add a network hop that the connector would have kept inside Anthropic's loop.
- Strict tool schemas restrict the JSON Schema keywords the servers may use; each server's `ToolSchemaRulesTest`
  enforces that subset.

## Alternatives considered

- **Messages API MCP connector.** Simplest wiring, no client to write. Rejected for facts 1 to 3: it needs public
  servers, it executes calls before the investigator can gate them, and it breaks replay.
- **Spring AI MCP client and server starters.** Less code. Rejected: it hides the transport and the client behind
  Spring AI's abstractions, which is the part this repository exists to show, and it ties two more modules to
  Spring AI's release cadence. The servlet transport in `mcp-core` is enough for Spring Boot.
- **Plain REST tools without MCP.** Rejected: tool discovery and the schema contract would have to be invented,
  and MCP is the protocol the agent ecosystem uses.
