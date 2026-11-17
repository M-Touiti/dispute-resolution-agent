# Development roadmap

Scope is fixed by [ADR 0001](adr/0001-scope-and-provider-port.md) and the [README](../README.md). This file tracks *how* the
milestones are executed: order of work, pinned versions, and the definition of done per milestone.
When a milestone closes, its Status cell records the completion date.

## Milestones

| Milestone | Done means | Status |
|---|---|---|
| M0 Skeleton | Repository public, ADR 0001, module layout, seeded synthetic data generator, three MCP servers answering `tools/list`, CI green (build, unit, CodeQL) | done 16 Nov 2026 |
| M1 Loop | One category end to end (Kafka in, triage, investigate via `ledger-mcp` + `policy-mcp`, structured decision, guard, audit rows, decision event out); recording and replay adapters; 30 golden cases; deterministic graders in CI | in progress since 16 Nov 2026 |
| M2 Production half | Approval gate + `actions-mcp` token verification; Resilience4j around the model; DLQ, replay, outbox; traces, metrics, dashboard; k6 and failure-injection first report; Kustomize manifests apply on a local cluster | |
| M3 Evidence | All categories incl. two adversarial; 150+ golden cases; live eval; `evals/REPORT.md`; six ADRs; README results table first; tag `v0.1.0` | |
| M4 Voice | Public write-up: what the evals found, the two hardest failures, what the approval gate caught | |

Scope cuts if time runs short, in order: reviewer page becomes a CLI; Kubernetes manifests wait for v0.2;
`policy-mcp` loses pgvector and keeps full-text; adversarial categories shrink to one. Never cut: evaluation
report, approval gate, recorded fixtures, ADRs.

## Pinned versions (verified against Maven Central and PyPI on 1 Nov 2026, re-baselined 16 Nov 2026)

| Concern | Artifact | Pin | Note |
|---|---|---|---|
| JDK | Temurin / Oracle | 21 (LTS) | Spring Boot 4 baseline is 17; 21 for virtual threads and records patterns |
| Orchestrator | `org.springframework.boot:spring-boot-starter-parent` | 4.1.2 | Latest GA line (4.2.0 is at RC2 on 16 Nov). Jackson 3, Servlet 6.1 |
| Model access | `com.anthropic:anthropic-java` | 3.8.0 | 3.0.0 (21 Oct 2026) moved to API version 2026-10-21; ships `OutputConfig`, `JsonOutputFormat`, `Tool.strict`, `CacheControlEphemeral` |
| MCP (Java) | `io.modelcontextprotocol.sdk:mcp-bom` | 2.2.0 | Latest GA. Use the `mcp` bundle = `mcp-core` + `mcp-json-jackson3`. 2.1.0 → 2.2.0 left all three `tools/list` contracts unchanged |
| MCP server transport | `HttpServletStreamableServerTransportProvider` in `mcp-core` | n/a | The `mcp-spring-webmvc` and `server-servlet` artifacts stopped at 0.18.x; in 2.x the Spring transports live in Spring AI. We register the servlet transport in Spring Boot via `ServletRegistrationBean` and do not depend on Spring AI |
| MCP client transport | `HttpClientStreamableHttpTransport` in `mcp-core` | n/a | JDK `HttpClient` based, Streamable HTTP |
| MCP (Python) | `mcp` | 2.3.0 | v2 line; v1.x is maintenance only |
| Python runtime | CPython | 3.13 | `mcp` requires >= 3.10 |
| Retrieval | `fastapi`, `pgvector`, `psycopg[binary]` | 0.163.1, 0.5.1, 3.3.9 | Embedding runtime chosen in ADR 0004 from the measurements below |
| JSON Schema (Java) | `com.networknt:json-schema-validator` | 3.0.8 | The version `mcp-json-jackson3` 2.2.0 uses for server-side input validation; the investigator pins the same one so client and servers validate alike. 3.0.9 exists; bump with the MCP SDK |
| Resilience | `io.github.resilience4j:resilience4j-spring-boot4` | 2.4.0 | The `-spring-boot4` module exists from 2.4.0 |
| Testing | `org.testcontainers:testcontainers-bom`; Python `testcontainers` | 2.0.5; 4.15.0 | Kafka (KRaft) and PostgreSQL modules. In Python 4.15 the PostgreSQL module is `testcontainers.community.postgres` (the old path is deprecated) |
| Observability | `io.opentelemetry.javaagent:opentelemetry-javaagent` | 2.33.1 | Plus manual spans for model and tool calls |
| Kafka | managed by Spring Boot 4.1.2 (`spring-kafka` 4.1.x) | | KRaft broker in compose |
| Container images (verified on Docker Hub 16 Nov 2026) | `pgvector/pgvector`, `apache/kafka`, `eclipse-temurin`, `python`, `ghcr.io/astral-sh/uv` | `0.8.7-pg18-trixie`, `4.2.2`, `21-jdk-noble`/`21-jre-noble`, `3.13.18-slim-trixie`, `0.11.33` | Broker matches the `kafka-clients` 4.2.2 that Boot manages; 4.4.0 is the newest GA |

Re-verify this table on the first day of each milestone. Dependabot raises weekly pull requests; a bump of the
MCP SDK or the Anthropic SDK must pass the `tools/list` contract tests and the deterministic eval suite.

**Re-verified 16 Nov 2026 (M1 kick-off).** Drift since 1 Nov: `anthropic-java` 3.3.0 → 3.8.0, `mcp-bom` 2.1.0 →
2.2.0, `fastapi` 0.162.0 → 0.163.1, `sentence-transformers` 6.2.0 → 6.3.0; Spring Boot 4.2.0 is still at RC2.
Task 1.1 applied the bumps above: all builds and the three `tools/list` contract tests pass unchanged. The
Anthropic SDK bump cost no re-baseline because no adapter or fixture exists yet.

### Embedding runtimes measured for ADR 0004 (16 Nov 2026)

Measured in a fresh `python:3.13.18-slim-trixie` container (the `policy-mcp` base image; Docker Desktop, 16 CPUs),
one new venv and model cache per runtime. The retrieval probe is 16 self-written policy passages and 16
paraphrased questions: a sanity check that the runtime retrieves sensibly, not a quality benchmark. Retrieval
quality is measured on the real corpus in 1.6.

| Runtime | Model | Install | venv | Model files | Cold load | 512 passages | Dim | Recall@1 / @3 | MRR |
|---|---|---|---|---|---|---|---|---|---|
| `sentence-transformers` 6.3.0 + CPU `torch` | `all-MiniLM-L6-v2` | 23 s | 1,112 MB | 88 MB | 4.5 s | 0.96 s | 384 | 0.56 / 0.88 | 0.71 |
| `fastembed` 0.9.1 (ONNX Runtime) | `all-MiniLM-L6-v2` | 7 s | 187 MB | 88 MB | 0.6 s | 1.00 s | 384 | 0.56 / 0.88 | 0.71 |
| `model2vec` 0.10.0 (static) | `potion-base-8M` | 5 s | 92 MB | 30 MB | 0.8 s | 0.01 s | 256 | 0.50 / 0.88 | 0.69 |

Reading: `fastembed` runs the same model as the `sentence-transformers` reference with identical rankings at a
sixth of the install size and a seventh of the cold load; it is the recommendation for ADR 0004. `model2vec` is
smaller and about a hundred times faster to embed, one probe question behind (not a significant difference on 16
questions); it is the fallback if the real-corpus quality check in 1.6 allows it. `sentence-transformers` brings
torch for no measured gain.

## M0 task breakdown

| # | Task | Output | Status |
|---|---|---|---|
| 0.1 | Verify toolchain and pin SDK versions | this file | done |
| 0.2 | Repository hygiene: `.gitignore`, `.gitattributes`, `.env.example`, `LICENSE`, `SECURITY.md` | root | done |
| 0.3 | ADR 0001 scope and provider port | `docs/adr/0001-*.md` | done |
| 0.4 | Directory layout per spec section 3, with a README per top-level folder | all folders | done |
| 0.5 | Maven multi-module skeleton for `investigator` (domain, application, adapters, bootstrap) with `LlmPort` and `McpToolPort` interfaces, no implementations | `investigator/` | done |
| 0.6 | Synthetic data generator (Python, seeded): cardholders, cards, merchants, transactions, authorisations, disputes with ground truth; writes CSV + SQL seed; unit tests check determinism for a seed | `data/` | done |
| 0.7 | `ledger-mcp` (Java): Spring Boot + `mcp-core` servlet Streamable HTTP; six tools registered with JSON schemas, stub handlers; contract test on `tools/list` | `mcp-servers/ledger-mcp/` | done |
| 0.8 | `actions-mcp` (Java): same shape; three tools, each with required `approval_token`; handlers reject until M2 | `mcp-servers/actions-mcp/` | done |
| 0.9 | `policy-mcp` (Python): `mcp` 2.3.0 + FastAPI, Streamable HTTP; two tools, stub handlers; pytest contract test on `tools/list` | `mcp-servers/policy-mcp/` | done |
| 0.10 | `docker-compose.yml` with PostgreSQL (pgvector image), Kafka (KRaft), the three servers | `deploy/compose/` | done |
| 0.11 | CI: `ci.yml` (Maven build + unit, Python lint + pytest, CodeQL Java and Python), Dependabot config | `.github/` | done |
| 0.12 | README with pitch, status, layout, how to run, ADR index | `README.md` | done |
| 0.13 | Create the public GitHub repository, push, confirm CI green | GitHub | done: public, CI, CodeQL and Dependabot green |

## M1 task breakdown

Done means: a `disputes.opened` event for an unauthorised-transaction dispute goes through triage, investigation
with `ledger-mcp` and `policy-mcp`, a validated structured decision, the policy guard and the audit log, and comes
out as a `disputes.decided` event; the 30 golden cases replay from recorded fixtures in CI with the deterministic
graders green; ADRs 0002, 0003, 0004 and 0006 accepted. Branches are named `m1/<task>`.

| # | Task | Output | Status |
|---|---|---|---|
| 1.1 | Re-baseline pins: `anthropic-java` 3.8.0, `mcp-bom` 2.2.0 (contract tests must pass), `fastapi` 0.163.1; choose and pin the Java JSON Schema validator, Python `testcontainers` and `psycopg`; measure the three embedding runtimes (install size, cold start, CI time) for ADR 0004 | this file, POMs, `pyproject.toml` | done |
| 1.2 | ADR 0002 manual loop versus the SDK tool runner; ADR 0003 self-hosted MCP servers and own client versus the Messages API MCP connector (and the raw SDK versus Spring AI); ADR 0004 local embedding model behind a port, with the runtime chosen from the 1.1 measurements | `docs/adr/0002`-`0004`, index | |
| 1.3 | Policy corpus: plain-English Markdown, one file per reason category plus general sections (time limits, provisional credit, evidence rules, actions), stable section ids and the categories each section applies to; a test in `data/` checks that every number in the corpus matches `datagen/policy.py` | `data/policy-corpus/` | |
| 1.4 | Golden set v1: 30 unauthorised-transaction cases (6 per scenario) selected deterministically from the generated ground truth and committed with expected decision, required evidence ids, allowed and forbidden actions; a selector script so M3 can grow it | `evals/golden/` | |
| 1.5 | `ledger-mcp` handlers over the `ledger` schema: bounded, paginated queries with the id and page-size rules the descriptions promise; Testcontainers PostgreSQL test that loads `data/generated/seed.sql` and fails (never skips) if the seed is missing; CI generates the seed before the Java jobs | `mcp-servers/ledger-mcp/` | |
| 1.6 | `policy-mcp` handlers: corpus ingestion into PostgreSQL (full text plus pgvector), an embedding port with the local-model adapter and a deterministic test double, reciprocal-rank fusion, `get_policy_section`; pytest with Testcontainers; scope cut on record: full text only | `mcp-servers/policy-mcp/` | |
| 1.7 | Investigator domain: case state machine for the M1 states (received, triaged, investigating, decided, escalated), `Decision` and its JSON schema (decision, confidence, rationale, cited evidence ids, cited policy sections, proposed actions), `PolicyGuard` (credit never above the disputed amount, time limit, cited evidence belongs to the case, cited sections exist, action allowed for the category), loop caps | `investigator/domain` | |
| 1.8 | Investigator application: triage (strict-schema tool call on the cheap model, deterministic fallback when it disagrees with the event) and investigate use cases over `LlmPort` and `McpToolPort`; stable system prompt with the cache breakpoint; tool results delimited and labelled untrusted; parallel tool calls answered in one message; schema failure retried once with the errors, then escalate; refusal and every cap escalate with the reason; audit records; tests with fake ports | `investigator/application` | |
| 1.9 | Skills: `load_skill` tool, skill index in the system prompt, `SKILL.md` for the unauthorised-transaction evidence checklist and for reading an authorisation trail | `skills/`, application | |
| 1.10 | `AnthropicAdapter`: request and response mapping (adaptive thinking by default, `output_config` effort and format, strict tools, cache control on the system block, non-streaming, `max_tokens` from configuration), usage and cost from a versioned price table in configuration, typed exception mapping; mapping tests offline, one live smoke test behind an environment variable | `investigator/adapters` (anthropic) | |
| 1.11 | `RecordingAdapter` and `ReplayAdapter`: canonical request hash (sorted tools, no timestamps), fixture format in `evals/fixtures/`, a replay miss fails; ADR 0006 recorded fixtures for CI and nightly live evaluation | adapters, `evals/fixtures/`, `docs/adr/0006` | |
| 1.12 | MCP client adapter: tool discovery at start-up from the ledger and policy servers, MCP schema to strict `ToolDefinition`, routing by tool name, per-server allowlist (no actions server in M1), timeouts; test against an in-process server built with the same SDK | `investigator/adapters` (mcp-client) | |
| 1.13 | Kafka and JPA adapters: `disputes.opened` consumer idempotent on dispute id with manual acknowledgement after persist; `cases` and `audit` tables managed by Flyway; `disputes.decided` published after commit (the outbox arrives in M2); Testcontainers Kafka and PostgreSQL test of one case end to end with the replay adapter | adapters, bootstrap | |
| 1.14 | Investigator in compose with its Dockerfile; `smoke.sh` publishes one `disputes.opened` and waits for `disputes.decided` | `deploy/compose/` | |
| 1.15 | Eval runner: an `eval` run mode of the investigator (replay adapter, one results file per golden case) and Python deterministic graders (decision matches, cited evidence exists and belongs to the case, cited sections exist, no forbidden action, cost and tool calls within budget, schema valid first try); a committed baseline and the regression rule | `evals/runner/` | |
| 1.16 | Recording session: one live run of the 30 cases through `RecordingAdapter`; fixtures and baseline committed; needs `ANTHROPIC_API_KEY` and a go-ahead on the estimated spend | `evals/fixtures/`, `evals/golden/baseline` | |
| 1.17 | CI eval job (compose, eval run, graders) required on `main`; README status and the first per-category numbers; sequence diagram of the loop; close M1 | `.github/`, `README.md`, `docs/diagrams/` | |

Order: 1.1 to 1.4 first (they fix the inputs); 1.5 and 1.6 can run alongside 1.7 to 1.9, which need no server;
1.10 to 1.13 need 1.8; 1.14 and 1.15 need 1.12 and 1.13; 1.16 needs everything; 1.17 closes.

## Decisions fixed at M1 kick-off

1. **The category is unauthorised transaction.** Its five scenarios exercise the most guard rules (time limit,
   credit limit, 3-D Secure, chip and PIN, fraud flag) and all three outcomes including escalate, and need no
   dispute documents. The other four categories and the adversarial ones arrive in M3.
2. **Replay first.** Everything in CI runs on fixtures. The only live calls in M1 are the recording session
   (1.16) and the opt-in adapter smoke test; both need an explicit go-ahead and the Console spend cap.
3. **The eval runner is split.** The investigator runs the cases (one loop, one code path); Python grades them,
   next to the data tooling. No second implementation of the loop for evaluation.
4. **Resilience, outbox, dead-letter and replay topics wait for M2.** M1 acknowledges after persist and publishes
   after commit; the M2 outbox replaces the publish, not the consumer.
5. **Spring Boot stays at 4.1.2** until 4.2 is GA. The SDK bumps in 1.1 are the last free ones: from 1.16 on,
   any SDK, prompt, tool schema or model change re-records the fixtures in the same pull request (ADR 0001).
6. **The embedding runtime is a measured choice, not a default** (ADR 0004). If none fits the CI budget, the
   recorded scope cut applies: `policy-mcp` keeps full-text search only.

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
