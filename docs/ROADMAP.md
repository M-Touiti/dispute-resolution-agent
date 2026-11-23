# Development roadmap

Scope is fixed by [ADR 0001](adr/0001-scope-and-provider-port.md) and the [README](../README.md). This file tracks *how* the
milestones are executed: order of work, pinned versions, and the definition of done per milestone.
When a milestone closes, its Status cell records the completion date.

## Milestones

| Milestone | Done means | Status |
|---|---|---|
| M0 Skeleton | Repository public, ADR 0001, module layout, seeded synthetic data generator, three MCP servers answering `tools/list`, CI green (build, unit, CodeQL) | done 16 Nov 2026 |
| M1 Loop | One category end to end (Kafka in, triage, investigate via `ledger-mcp` + `policy-mcp`, structured decision, guard, audit rows, decision event out); recording and replay adapters; 30 golden cases; deterministic graders in CI | done 22 Nov 2026 |
| M2 Production half | Approval gate + `actions-mcp` token verification; Resilience4j around the model; DLQ, replay, outbox; traces, metrics, dashboard; k6 and failure-injection first report; Kustomize manifests apply on a local cluster | in progress since 23 Nov 2026 |
| M3 Evidence | All categories incl. two adversarial; 150+ golden cases; live eval; `evals/REPORT.md`; six ADRs; README results table first; tag `v0.1.0` | |
| M4 Voice | Public write-up: what the evals found, the two hardest failures, what the approval gate caught | |

Scope cuts if time runs short, in order: reviewer page becomes a CLI; Kubernetes manifests wait for v0.2;
`policy-mcp` loses pgvector and keeps full-text; adversarial categories shrink to one. Never cut: evaluation
report, approval gate, recorded fixtures, ADRs.

## Pinned versions (verified against Maven Central and PyPI on 1 Nov 2026, re-baselined 16 Nov 2026)

| Concern | Artifact | Pin | Note |
|---|---|---|---|
| JDK | Temurin / Oracle | 21 (LTS) | Spring Boot 4 baseline is 17; 21 for virtual threads and records patterns |
| Orchestrator | `org.springframework.boot:spring-boot-starter-parent` | 4.2.0 | GA 19 Nov 2026, applied in 2.1 (from 4.1.2). Spring Framework 7.1, Jackson 3.1, Servlet 6.1 |
| Model access | `com.anthropic:anthropic-java` | 3.12.0 | 3.8.0 → 3.12.0 in 2.1 left every fixture key unchanged. 3.0.0 (21 Oct 2026) moved to API version 2026-10-21; ships `OutputConfig`, `JsonOutputFormat`, `Tool.strict`, `CacheControlEphemeral` |
| MCP (Java) | `io.modelcontextprotocol.sdk:mcp-bom` | 2.2.0 | Latest GA. Use the `mcp` bundle = `mcp-core` + `mcp-json-jackson3`. 2.1.0 → 2.2.0 left all three `tools/list` contracts unchanged |
| MCP server transport | `HttpServletStreamableServerTransportProvider` in `mcp-core` | n/a | The `mcp-spring-webmvc` and `server-servlet` artifacts stopped at 0.18.x; in 2.x the Spring transports live in Spring AI. We register the servlet transport in Spring Boot via `ServletRegistrationBean` and do not depend on Spring AI |
| MCP client transport | `HttpClientStreamableHttpTransport` in `mcp-core` | n/a | JDK `HttpClient` based, Streamable HTTP |
| MCP (Python) | `mcp` | 2.3.0 | v2 line; v1.x is maintenance only |
| Python runtime | CPython | 3.13 | `mcp` requires >= 3.10 |
| Retrieval | `fastapi`, `psycopg[binary,pool]` (`psycopg-pool`), `fastembed` | 0.165.0, 3.3.9 (3.3.3), 0.9.1 | Model `BAAI/bge-small-en-v1.5`, quantised ONNX, pinned by SHA-256 (ADR 0007). The `pgvector` Python package is not needed: vectors are passed as text and cast in SQL |
| JSON Schema (Java) | `com.networknt:json-schema-validator` | 3.0.8 | The version `mcp-json-jackson3` 2.2.0 uses for server-side input validation; the investigator pins the same one so client and servers validate alike. 3.0.9 exists; bump with the MCP SDK |
| Resilience | `io.github.resilience4j:resilience4j-spring-boot4` | 2.4.0 | The `-spring-boot4` module exists from 2.4.0. Probed on Boot 4.2.0 in 2.1: binds and runs retry, circuit breaker, bulkhead and time limiter |
| Testing | `org.testcontainers:testcontainers-bom`; Python `testcontainers` | 2.0.5; 4.15.0 | Kafka (KRaft) and PostgreSQL modules. In Python 4.15 the PostgreSQL module is `testcontainers.community.postgres` (the old path is deprecated) |
| Observability | Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter | managed by Boot (Tracing 1.8.0, OpenTelemetry 1.68.0) | Replaces the Java agent (decision 3 at the M2 kick-off); manual spans for model and tool calls |
| Kafka | managed by Spring Boot 4.2.0 (`spring-kafka` 4.2.0, `kafka-clients` 4.4.0) | | KRaft broker in compose |
| Container images (verified on Docker Hub 16 Nov 2026) | `pgvector/pgvector`, `apache/kafka`, `eclipse-temurin`, `python`, `ghcr.io/astral-sh/uv` | `0.8.7-pg18-trixie`, `4.4.0` (from 4.2.2 in 2.1), `21-jdk-noble`/`21-jre-noble`, `3.13.18-slim-trixie`, `0.11.33` | Broker matches the `kafka-clients` 4.2.2 that Boot manages; 4.4.0 is the newest GA |

Re-verify this table on the first day of each milestone. Dependabot raises weekly pull requests; a bump of the
MCP SDK or the Anthropic SDK must pass the `tools/list` contract tests and the deterministic eval suite.

**Re-verified 16 Nov 2026 (M1 kick-off).** Drift since 1 Nov: `anthropic-java` 3.3.0 → 3.8.0, `mcp-bom` 2.1.0 →
2.2.0, `fastapi` 0.162.0 → 0.163.1, `sentence-transformers` 6.2.0 → 6.3.0; Spring Boot 4.2.0 is still at RC2.
Task 1.1 applied the bumps above: all builds and the three `tools/list` contract tests pass unchanged. The
Anthropic SDK bump cost no re-baseline because no adapter or fixture exists yet.

**Re-verified 23 Nov 2026 (M2 kick-off).** Drift since 16 Nov: Spring Boot 4.2.0 is GA (19 Nov; it manages Spring
Framework 7.1.0, Jackson 3.1.8, `spring-kafka` 4.2.0, `kafka-clients` 4.4.0, Flyway 12.11.0, Micrometer 1.18.0,
Micrometer Tracing 1.8.0, OpenTelemetry 1.68.0, Testcontainers 2.0.5); `anthropic-java` 3.8.0 → 3.12.0 (minor);
`fastapi` 0.163.1 → 0.165.0; `mcp-bom`, `mcp`, `resilience4j-spring-boot4`, `fastembed`, `psycopg` and Python
`testcontainers` unchanged; `json-schema-validator` 3.0.9 exists but stays at 3.0.8 with the MCP SDK. Images: the
Kafka image follows the managed client to `4.4.0`; `pgvector` and `python` unchanged. New for M2 (checked on Docker
Hub): `prom/prometheus` v3.16.0, `grafana/grafana` 13.3.1, `grafana/tempo` 2.10.10, `grafana/k6` 1.8.3. The
`opentelemetry-javaagent` row is retired: tracing goes through Micrometer Tracing and the OTLP exporter that Boot
manages (decision 3 at the M2 kick-off). Task 2.1 applies the bumps.

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
| 1.2 | ADR 0002 manual loop versus the SDK tool runner; ADR 0003 self-hosted MCP servers and own client versus the Messages API MCP connector (and the raw SDK versus Spring AI); ADR 0004 local embedding model behind a port, with the runtime chosen from the 1.1 measurements | `docs/adr/0002`-`0004`, index | done |
| 1.3 | Policy corpus: plain-English Markdown, one file per reason category plus general sections (time limits, provisional credit, evidence rules, actions), stable section ids and the categories each section applies to; a test in `data/` checks that every number in the corpus matches `datagen/policy.py` | `data/policy-corpus/` | done |
| 1.4 | Golden set v1: 30 unauthorised-transaction cases (6 per scenario) selected deterministically from the generated ground truth and committed with expected decision, required evidence ids, allowed and forbidden actions; a selector script so M3 can grow it | `evals/golden/` | done |
| 1.5 | `ledger-mcp` handlers over the `ledger` schema: bounded, paginated queries with the id and page-size rules the descriptions promise; Testcontainers PostgreSQL test that loads `data/generated/seed.sql` and fails (never skips) if the seed is missing; CI generates the seed before the Java jobs | `mcp-servers/ledger-mcp/` | done |
| 1.6 | `policy-mcp` handlers: corpus ingestion into PostgreSQL (full text plus pgvector), an embedding port with the local-model adapter and a deterministic test double, reciprocal-rank fusion, `get_policy_section`; pytest with Testcontainers; scope cut on record: full text only | `mcp-servers/policy-mcp/` | done; model and fusion per ADR 0007 |
| 1.7 | Investigator domain: case state machine for the M1 states (received, triaged, investigating, decided, escalated), `Decision` and its JSON schema (decision, confidence, rationale, cited evidence ids, cited policy sections, proposed actions), `PolicyGuard` (credit never above the disputed amount, time limit, cited evidence belongs to the case, cited sections exist, action allowed for the category), loop caps | `investigator/domain` | done |
| 1.8 | Investigator application: triage (strict-schema tool call on the cheap model, deterministic fallback when it disagrees with the event) and investigate use cases over `LlmPort` and `McpToolPort`; stable system prompt with the cache breakpoint; tool results delimited and labelled untrusted; parallel tool calls answered in one message; schema failure retried once with the errors, then escalate; refusal and every cap escalate with the reason; audit records; tests with fake ports | `investigator/application` | done |
| 1.9 | Skills: `load_skill` tool, skill index in the system prompt, `SKILL.md` for the unauthorised-transaction evidence checklist and for reading an authorisation trail | `skills/`, application | done |
| 1.10 | `AnthropicAdapter`: request and response mapping (adaptive thinking by default, `output_config` effort and format, strict tools, cache control on the system block, non-streaming, `max_tokens` from configuration), usage and cost from a versioned price table in configuration, typed exception mapping; mapping tests offline, one live smoke test behind an environment variable | `investigator/adapters` (anthropic) | done; live check opt-in, not yet run |
| 1.11 | `RecordingAdapter` and `ReplayAdapter`: canonical request hash (sorted tools, no timestamps), fixture format in `evals/fixtures/`, a replay miss fails; ADR 0006 recorded fixtures for CI and nightly live evaluation | adapters, `evals/fixtures/`, `docs/adr/0006` | done |
| 1.12 | MCP client adapter: tool discovery at start-up from the ledger and policy servers, MCP schema to strict `ToolDefinition`, routing by tool name, per-server allowlist (no actions server in M1), timeouts; test against an in-process server built with the same SDK | `investigator/adapters` (mcp-client) | done |
| 1.13 | Kafka and JPA adapters: `disputes.opened` consumer idempotent on dispute id with manual acknowledgement after persist; `cases` and `audit` tables managed by Flyway; `disputes.decided` published after commit (the outbox arrives in M2); Testcontainers Kafka and PostgreSQL test of one case end to end with the replay adapter | adapters, bootstrap | done; JDBC rather than JPA |
| 1.14 | Investigator in compose with its Dockerfile; `smoke.sh` publishes one `disputes.opened` and waits for `disputes.decided` | `deploy/compose/` | done; a case escalates until fixtures exist (1.16) |
| 1.15 | Eval runner: an `eval` run mode of the investigator (replay adapter, one results file per golden case) and Python deterministic graders (decision matches, cited evidence exists and belongs to the case, cited sections exist, no forbidden action, cost and tool calls within budget, schema valid first try); a committed baseline and the regression rule | `evals/runner/` | done; the baseline is committed with the fixtures in 1.16 |
| 1.16 | Recording session: one live run of the 30 cases through `RecordingAdapter`; fixtures and baseline committed; needs `ANTHROPIC_API_KEY` and a go-ahead on the estimated spend | `evals/fixtures/`, `evals/golden/baseline` | done; 149 fixtures, $1.93, accuracy 30/30. The first recording ($2.01) exposed two generator flaws (expired cards in use, contradicted "never shopped there" statements); generator v3 fixed both and the set was re-recorded |
| 1.17 | CI eval job (compose, eval run, graders) required on `main`; README status and the first per-category numbers; sequence diagram of the loop; close M1 | `.github/`, `README.md`, `docs/diagrams/` | done; the `Evaluation (replay)` check is made required in the branch protection of `main` |

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

## M2 task breakdown

Done means: a decided case with a proposed action waits for a person; an approval mints a signed, single-use token
bound to the case, the action and its arguments; `actions-mcp` verifies the token itself and records the write
once, and a rejection or a bad token changes nothing; the model call sits behind a time limiter, bounded retry,
bulkhead and circuit breaker, and a case the model cannot serve is parked and re-driven through `disputes.replay`
rather than lost; poison events reach `disputes.dlq` without stalling the partition; every outgoing event goes
through an outbox; one trace per case and the Prometheus metrics of the design's observability section show in a
provisioned Grafana dashboard; the k6 and failure-injection runs have a first report with their caveats; the
Kustomize manifests validate in CI and apply on a local cluster; ADRs 0005 and 0008 accepted; the 30 golden cases
still replay from the M1 fixtures unchanged. Branches are named `m2/<task>`.

| # | Task | Output | Status |
|---|---|---|---|
| 2.1 | Re-baseline pins (above): Spring Boot 4.2.0 with the Kafka image at 4.4.0, `anthropic-java` 3.12.0, `fastapi` 0.165.0; confirm `resilience4j-spring-boot4` 2.4.0 starts on Boot 4.2 (if not, M2 stays on 4.1.2: the breaker matters more than the Boot minor); the three `tools/list` contracts and the replay evaluation must pass unchanged; the SDK bump is checked by the offline mapping tests and one opt-in live check (under one cent, needs the go-ahead) | POMs, `pyproject.toml`, compose, this file | done; all builds, the three `tools/list` contracts, the compose smoke and the replay evaluation (30/30, every fixture hit) pass unchanged. The opt-in live check is not run: no go-ahead yet, and the replay already shows the request shape the fixtures were recorded with is unchanged |
| 2.2 | ADR 0005 approval tokens enforced inside the write server (Ed25519, binding, single use, idempotency); ADR 0008 reliability and observability as decorators around the ports (parked cases, replay, outbox, Micrometer Tracing instead of an agent) | `docs/adr/0005`, `docs/adr/0008`, index | done; ADR 0005 makes the token a standard compact JWS (`alg` `EdDSA`), still JDK only, and ADR 0008 adds a retryable `UNAVAILABLE` kind for an open breaker or a full bulkhead; rows 2.5, 2.6 and 2.9 follow |
| 2.3 | Domain: case states after `DECIDED` (`AWAITING_APPROVAL`, `APPROVED`, `REJECTED`, `ACTED`, `ACTION_FAILED`) and the parked state `AWAITING_RETRY` with its transitions and replay count; `ApprovalRequest` and `ApprovalToken` values (case id, action, SHA-256 of the canonical arguments, token id, issued and expiry instants); the audit events for approval, action and parking; state-machine tests | `investigator/domain` | done; `CaseState.isTerminal()` now means no way out (`DECIDED` leads to approval) and `endsInvestigation()` marks the investigation's outcome; `CaseOutcome` uses the latter. Behaviour unchanged: smoke and the replay evaluation (30/30) pass |
| 2.4 | Approval gate in the application: a decision with an action moves the case to `AWAITING_APPROVAL` and raises `disputes.approval-requested`; `ApproveAction` and `RejectAction` use cases (reviewer id, note) behind a `TokenSigner` port and an `ActionPort`; on approval the investigator signs, calls the action, records the result (`ACTED` or `ACTION_FAILED` with the server's reason) and audits every step; the model's tool list is untouched; tests with fake ports | `investigator/application` | done, with the adapters the request side needs so the service stays true: Flyway `V2` (approval columns, guarded updates), `DisputeCase.restore`, `JdbcCaseStore.find` and `update`, the `disputes.approval-requested` topic. A decided case is now stored `AWAITING_APPROVAL`; `ApproveAction` and `RejectAction` are tested with fakes and wired in 2.7 |
| 2.5 | Token adapter and key handling: Ed25519 with the JDK (`KeyPairGenerator`, `Signature`; no library), compact JWS `header.payload.signature` with `alg` `EdDSA` and JCS-canonical argument hashes (ADR 0005); `docs/approval-token.md` documents the format; `docs/approval-token/vectors.json` holds test vectors (keys, tokens, expected verdicts) that both Java projects' tests read; keys from configuration (`APPROVAL_SIGNING_KEY`, `APPROVAL_VERIFY_KEYS`), generated by a compose init service into a shared volume so nothing is committed | `investigator/adapters`, `docs/`, `deploy/compose` | |
| 2.6 | `actions-mcp` verification and writes: signature, expiry, binding (the argument hash recomputed from the call), single use recorded in its own `actions` schema as role `actions_service` in the same transaction as the write; the same token id with the same arguments returns the stored result (idempotent retry), with different arguments is rejected; business limits read from `intake.disputes` (credit at most the disputed amount, dispute not already closed); Testcontainers test on the seed; the `tools/list` contract must not change (the descriptions already promise this) | `mcp-servers/actions-mcp/`, `deploy/compose/postgres/initdb` | |
| 2.7 | Reviewer API in the investigator: `GET /reviews` (pending), `POST /reviews/{id}/approve` and `/reject` with a reviewer id and note, `409` on a state conflict, a static API key header from the environment as the stand-in for SSO; the MCP client's allowlist gains `actions-mcp` for the investigator's own calls only; `smoke.sh` approves a golden case with curl and checks the action in `actions-mcp`'s tables, then rejects another | `investigator/adapters` (web), bootstrap, `deploy/compose/smoke.sh` | |
| 2.8 | Outbox: `investigation.outbox` written in the same transaction as the case; a polling publisher sends in id order and marks rows sent; the consumer acknowledges after the save, no longer after the publish; `KafkaDecisionPublisher` becomes the outbox writer; Testcontainers test that a publish failure after the save still delivers the event on the next poll | `investigator/adapters` (persistence, kafka) | |
| 2.9 | Resilience4j around `LlmPort` as a decorator in the adapters: time limiter, retry with jitter on the transient kinds (rate limited, overloaded, server, timeout), bulkhead on concurrent model calls, circuit breaker; the SDK's `max-retries` goes to 0 (one place); an open breaker or a full bulkhead surfaces as a new retryable `LlmCallException` kind, `UNAVAILABLE`; an open breaker or a transient failure after the retries parks the case `AWAITING_RETRY` with the original event stored; permanent kinds (fixture miss, authentication, invalid request) still escalate; tests with a fake port and a fake clock | `investigator/adapters` (resilience), bootstrap, `application.yml` | |
| 2.10 | Dead-letter and replay: a `DeadLetterPublishingRecoverer` sends malformed events and cases that fail the error handler's retries to `disputes.dlq` with reason headers; a scheduled sweep re-publishes parked cases to `disputes.replay` when the breaker allows, up to a replay cap after which the case escalates `DEPENDENCY_UNAVAILABLE`; the listener consumes both topics and re-runs a parked case from scratch; Testcontainers test: a poison message reaches the dead-letter topic without stalling the partition, a parked case recovers on replay | `investigator/adapters` (kafka), bootstrap | |
| 2.11 | Traces, metrics and logs as decorators in `adapters/otel` (Micrometer Observation and Tracing with the OpenTelemetry bridge and OTLP exporter; no agent): one span per case opened by the listener and the eval runner, a span per model call (model id, stop reason, input, output, cache read and cache creation tokens, cost, effort), per tool call (server, tool, argument hash, outcome), the guard, the approval wait and each action; Prometheus metrics: cases by outcome, tokens and cost per case (histograms), model latency, tool error rate by server and tool, unsafe proposals, approval latency, cache hit ratio; structured JSON logs keyed by case id with card-number redaction; tests on the simple tracer and meter registry | `investigator/adapters` (otel), bootstrap | |
| 2.12 | Compose observability: Prometheus scraping the Java services, Tempo receiving OTLP, Grafana with provisioned datasources and the dashboard JSON in the repository; `policy-mcp` exposes `/metrics`; `smoke.sh` checks that Prometheus sees every target up and that a case's trace exists in Tempo | `deploy/compose`, `deploy/compose/grafana` | |
| 2.13 | k6 load test: a `performance/shim` (Python, compose profile `perf`) that publishes `disputes.opened` and tracks `disputes.decided`; scripts for cases per second at 1 and 3 investigator replicas, p99 end to end, consumer lag, database connections, outbox drain time, all on the replay adapter; the report in `performance/README.md` in the before/after shape with the caveats (one laptop, replayed model); CI matrix and Dependabot entries for the shim | `performance/` | |
| 2.14 | Failure injection: a stalled `policy-mcp` (the time limiter and bulkhead hold), a malformed event (reaches the dead-letter topic, the partition moves on), the replay adapter forced to answer "overloaded" for 40 seconds through a fault switch (cases park and recover on replay); scripted, with the measured results added to the report | `performance/`, `investigator/adapters` (fixtures) | |
| 2.15 | Kubernetes: Kustomize base and a `local` overlay, HPA on the investigator, readiness and liveness probes, resource limits, secrets for the API key and the signing keys, a ServiceMonitor, an optional Argo CD application; CI validates with `kubectl kustomize` and `kubeconform`; applied on a local `kind` cluster by hand (needs the user's laptop; no cluster in CI) | `deploy/k8s/`, `.github/` | |
| 2.16 | Images and scanning: a release workflow builds the four images and pushes them to GitHub Container Registry on a tag; Trivy scans the images on pull requests (the `SECURITY.md` promise); Actions pinned to commit SHAs | `.github/workflows/release.yml`, `ci.yml`, `SECURITY.md` | |
| 2.17 | Close M2: README status and "What exists today", the approval gate and parking in `docs/diagrams/` (a second sequence diagram), the load and failure numbers linked from the README with their caveats, `CLAUDE.md` commands; the compose smoke test and the evaluation job stay required on `main` | `README.md`, `docs/diagrams/`, `CLAUDE.md`, this file | |

Order: 2.1 and 2.2 first (they fix the inputs). The gate chain is 2.3 → 2.4 → 2.5 → 2.6 → 2.7, in that order. The
reliability chain is 2.8, then 2.9, then 2.10 (replay needs the stored event from 2.8 and the breaker from 2.9).
2.11 needs 2.4 for the approval spans but can start after 2.2; 2.12 needs 2.11. The two chains and 2.11 run in
parallel. 2.13 and 2.14 need 2.8 to 2.12; 2.15 and 2.16 need the compose stack of 2.12; 2.17 closes.

## Decisions fixed at M2 kick-off

1. **M2 changes nothing the model sees.** The write tools never enter the model's tool list; approval happens
   after the decision; the prompt, skills, tool descriptions, model and effort stay as recorded. The M1 fixtures
   therefore stay valid and no recording session is planned in M2. A task that would change the prompt waits for
   M3, where the golden set grows and is re-recorded anyway.
2. **Approval tokens are Ed25519 with the JDK, no library** (ADR 0005). The investigator holds the private key and
   signs; `actions-mcp` holds only the public key and verifies: a compromised write server cannot mint tokens, and
   nothing but the investigator can. The payload binds the case id, the action, the SHA-256 of the canonical
   arguments, a token id, and issue and expiry instants (five minutes). Single use is recorded by `actions-mcp` in
   its own schema in the same transaction as the write; the token id is also the idempotency key, so a retried call
   with the same arguments returns the stored result instead of a second write. The format is pinned by shared
   test vectors read by both projects' tests, so the two implementations cannot drift apart silently.
3. **Reliability and observability are decorators around the ports** (ADR 0008). Resilience4j wraps `LlmPort`;
   spans and meters wrap `LlmPort`, `McpToolPort` and the audit log; the case span is opened by the Kafka
   listener and the eval runner. `application` stays framework-free (the enforcer rule holds) and every decorator
   is testable with a fake port. Tracing uses Micrometer Tracing with the OpenTelemetry bridge and the OTLP
   exporter that Boot manages, not the Java agent: the agent is absent from the build and the tests, and the
   manual spans with the cost attributes are the point. Retries move to Resilience4j and the SDK's go to 0: one
   place, as `CLAUDE.md` demands.
4. **A case the model cannot serve is parked, not escalated.** Transient kinds (rate limited, overloaded, server
   error, timeout, open breaker, an unreachable tool server) park the case as `AWAITING_RETRY` with its original
   event stored; a scheduled sweep re-drives parked cases through `disputes.replay` when the breaker allows,
   re-running them from scratch (the loop is stateless per case and the fixtures make a re-run cheap), up to a
   replay cap after which the case escalates `DEPENDENCY_UNAVAILABLE`. Permanent kinds (a fixture miss,
   authentication, an invalid request) still escalate at once, so a missing fixture in CI fails fast instead of
   looping.
5. **The outbox is a polling publisher inside the investigator**, not change-data capture: one table in the case's
   transaction, a poller, at-least-once delivery, consumers idempotent on the dispute id (already the rule since
   M1). The consumer acknowledges after the save; the publish is no longer on the consumer's path.
6. **The reviewer is an API, not a page.** The first scope cut of the plan is applied up front: `smoke.sh` and the
   failure scripts approve with curl, and a static API key header stands in for SSO. A page is a candidate for
   v0.2; the gate, the token and the audit are what M2 proves.
7. **The observability stack is Prometheus, Tempo and Grafana in compose, with no collector:** the investigator
   exports OTLP straight to Tempo and Prometheus scrapes the actuator endpoints. One laptop; every number carries
   that caveat. Kubernetes manifests are validated in CI with `kubeconform` and applied on `kind` by hand; CI has
   no cluster.
8. **Spring Boot moves to 4.2.0** now that it is GA, in 2.1, with the Kafka image following the managed client.
   `resilience4j-spring-boot4` 2.4.0 is the only Boot 4 line; if it does not start on 4.2, M2 stays on 4.1.2 and
   says so here.
9. **The load test measures the platform, not the model.** k6 drives Kafka through a small shim with the replay
   adapter, so a run costs nothing and the p99 is the pipeline's. The fault switch for failure injection lives in
   the fixtures adapter, so the live adapter is never touched.

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
| 0007 | Embedding model and fusion weights measured on the policy corpus | M1 |
| 0008 | Reliability and observability as decorators around the ports | M2 |
