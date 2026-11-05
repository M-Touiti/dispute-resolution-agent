# Development roadmap

Scope is fixed by [ADR 0001](adr/0001-scope-and-provider-port.md) and the [README](../README.md). This file tracks *how* the
milestones are executed: order of work, pinned versions, and the definition of done per milestone.
When a milestone closes, its Status cell records the completion date.

## Milestones

| Milestone | Done means | Status |
|---|---|---|
| M0 Skeleton | Repository public, ADR 0001, module layout, seeded synthetic data generator, three MCP servers answering `tools/list`, CI green (build, unit, CodeQL) | in progress |
| M1 Loop | One category end to end (Kafka in, triage, investigate via `ledger-mcp` + `policy-mcp`, structured decision, guard, audit rows, decision event out); recording and replay adapters; 30 golden cases; deterministic graders in CI | |
| M2 Production half | Approval gate + `actions-mcp` token verification; Resilience4j around the model; DLQ, replay, outbox; traces, metrics, dashboard; k6 and failure-injection first report; Kustomize manifests apply on a local cluster | |
| M3 Evidence | All categories incl. two adversarial; 150+ golden cases; live eval; `evals/REPORT.md`; six ADRs; README results table first; tag `v0.1.0` | |
| M4 Voice | Public write-up: what the evals found, the two hardest failures, what the approval gate caught | |

Scope cuts if time runs short, in order: reviewer page becomes a CLI; Kubernetes manifests wait for v0.2;
`policy-mcp` loses pgvector and keeps full-text; adversarial categories shrink to one. Never cut: evaluation
report, approval gate, recorded fixtures, ADRs.

## Pinned versions (verified against Maven Central and PyPI on 1 Nov 2026)

| Concern | Artifact | Pin | Note |
|---|---|---|---|
| JDK | Temurin / Oracle | 21 (LTS) | Spring Boot 4 baseline is 17; 21 for virtual threads and records patterns |
| Orchestrator | `org.springframework.boot:spring-boot-starter-parent` | 4.1.2 | Latest GA line (4.2.0 is at RC1). Jackson 3, Servlet 6.1 |
| Model access | `com.anthropic:anthropic-java` | 3.3.0 | 3.0.0 (21 Oct 2026) moved to API version 2026-10-21; ships `OutputConfig`, `JsonOutputFormat`, `Tool.strict`, `CacheControlEphemeral` |
| MCP (Java) | `io.modelcontextprotocol.sdk:mcp-bom` | 2.1.0 | Latest GA (2.2.0-M1 is a milestone). Use the `mcp` bundle = `mcp-core` + `mcp-json-jackson3` |
| MCP server transport | `HttpServletStreamableServerTransportProvider` in `mcp-core` | n/a | The `mcp-spring-webmvc` and `server-servlet` artifacts stopped at 0.18.x; in 2.x the Spring transports live in Spring AI. We register the servlet transport in Spring Boot via `ServletRegistrationBean` and do not depend on Spring AI |
| MCP client transport | `HttpClientStreamableHttpTransport` in `mcp-core` | n/a | JDK `HttpClient` based, Streamable HTTP |
| MCP (Python) | `mcp` | 2.3.0 | v2 line; v1.x is maintenance only |
| Python runtime | CPython | 3.13 | `mcp` requires >= 3.10 |
| Retrieval | `fastapi` 0.162.0, `pgvector` 0.5.1, `sentence-transformers` 6.2.0 | | Local embedding model behind a port (ADR 0004) |
| Resilience | `io.github.resilience4j:resilience4j-spring-boot4` | 2.4.0 | The `-spring-boot4` module exists from 2.4.0 |
| Testing | `org.testcontainers:testcontainers-bom` | 2.0.5 | Kafka (KRaft) and PostgreSQL modules |
| Observability | `io.opentelemetry.javaagent:opentelemetry-javaagent` | 2.33.1 | Plus manual spans for model and tool calls |
| Kafka | managed by Spring Boot 4.1.2 (`spring-kafka` 4.1.x) | | KRaft broker in compose |

Re-verify this table on the first day of each milestone. Dependabot raises weekly pull requests; a bump of the
MCP SDK or the Anthropic SDK must pass the `tools/list` contract tests and the deterministic eval suite.

## M0 task breakdown

| # | Task | Output | Status |
|---|---|---|---|
| 0.1 | Verify toolchain and pin SDK versions | this file | done |
| 0.2 | Repository hygiene: `.gitignore`, `.gitattributes`, `.env.example`, `LICENSE`, `SECURITY.md` | root | done |
| 0.3 | ADR 0001 scope and provider port | `docs/adr/0001-*.md` | done |
| 0.4 | Directory layout per spec section 3, with a README per top-level folder | all folders | done |
| 0.5 | Maven multi-module skeleton for `investigator` (domain, application, adapters, bootstrap) with `LlmPort` and `McpToolPort` interfaces, no implementations | `investigator/` | |
| 0.6 | Synthetic data generator (Python, seeded): cardholders, cards, merchants, transactions, authorisations, disputes with ground truth; writes CSV + SQL seed; unit tests check determinism for a seed | `data/` | |
| 0.7 | `ledger-mcp` (Java): Spring Boot + `mcp-core` servlet Streamable HTTP; six tools registered with JSON schemas, stub handlers; contract test on `tools/list` | `mcp-servers/ledger-mcp/` | |
| 0.8 | `actions-mcp` (Java): same shape; three tools, each with required `approval_token`; handlers reject until M2 | `mcp-servers/actions-mcp/` | |
| 0.9 | `policy-mcp` (Python): `mcp` 2.3.0 + FastAPI, Streamable HTTP; two tools, stub handlers; pytest contract test on `tools/list` | `mcp-servers/policy-mcp/` | |
| 0.10 | `docker-compose.yml` with PostgreSQL (pgvector image), Kafka (KRaft), the three servers | `deploy/compose/` | |
| 0.11 | CI: `ci.yml` (Maven build + unit, Python lint + pytest, CodeQL Java and Python), Dependabot config | `.github/` | |
| 0.12 | README with pitch, status, layout, how to run, ADR index | `README.md` | |
| 0.13 | Create the public GitHub repository, push, confirm CI green | GitHub | needs `gh` CLI and your account |

## Architecture decisions fixed at M0

1. **Hexagonal `investigator`.** `domain` has no dependencies; `application` holds ports and use cases; `adapters`
   holds kafka, jpa, anthropic, mcp-client, otel; `bootstrap` is the Spring Boot application. Same shape as
   `card-subscription-service`.
2. **Raw MCP Java SDK, not Spring AI.** The point is to show the protocol; Spring AI would hide the client and
   the transport. Servers use the servlet Streamable HTTP transport from `mcp-core`; the client uses the JDK
   `HttpClient` transport. Recorded in ADR 0003 (M1).
3. **Manual agent loop over the Messages API, not the SDK tool runner.** ADR 0002 (M1).
4. **Model ids and effort in configuration, never in code.** ADR 0001.
5. **One API key.** Embeddings are local (ADR 0004, M1).
6. **Approval tokens verified in the write server, not only in the client.** ADR 0005 (M2).
7. **Fixtures by request hash; replay in CI; nightly live slice with a spend cap.** ADR 0006 (M1/M3).

## ADR index

| ADR | Title | Milestone |
|---|---|---|
| 0001 | Scope and the LLM provider port | M0 |
| 0002 | Manual agent loop versus the SDK tool runner | M1 |
| 0003 | Self-hosted MCP servers and client versus the Messages API MCP connector | M1 |
| 0004 | Local sentence-embedding model behind a port | M1 |
| 0005 | Approval tokens enforced inside the write server | M2 |
| 0006 | Recorded fixtures for CI and nightly live evaluation | M1 / M3 |
