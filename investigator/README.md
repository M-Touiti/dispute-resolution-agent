# investigator

Java 21, Spring Boot 4.2.0, Maven multi-module (hexagonal):

- `domain`: entities, value objects, the case state machine (investigation, parking and replay, approval and
  action), approval values (`ApprovalRequest`, `Review`, `ApprovalToken`). No dependencies outside the JDK; a Maven enforcer rule
  fails the build otherwise.
- `application`: ports (`LlmPort`, `McpToolPort`, repositories, clock) and use cases (triage, investigate, guard,
  act). Depends on `domain` only, also enforced.
- `adapters`: kafka, persistence (JDBC), anthropic, fixtures, mcpclient, json, skills; otel in M2. The only module that sees SDK types.
- `bootstrap`: the Spring Boot application, configuration, wiring.

Base package `dev.disputeagent.investigator`. SDK versions are pinned in the parent `pom.xml` (see
`docs/ROADMAP.md`).

## Build

From this directory (the wrapper pins Maven 3.9.12; on Windows use `mvnw.cmd`):

The bootstrap tests need Docker (Testcontainers).

```sh
./mvnw verify                                        # build and run all tests
./mvnw -pl application test                          # one module
./mvnw -pl application test -Dtest=LlmRequestTest    # one test class
./mvnw -pl application test -Dtest='LlmRequestTest#rejectsDuplicateToolNames'   # one test method
```

## Domain (M1)

- `decision`: `Decision` (outcome, confidence, rationale, cited evidence ids, cited policy sections, proposed
  actions) and `decision.schema.json`, the structured-output schema the model answers in. `DecisionSchemaTest`
  keeps the schema inside the structured-output subset and checks its enums and action names against the Java
  types and against the actions-mcp tool schemas.
- `guard`: `PolicyGuard` checks every decision deterministically and reports every broken rule. A credit is refused
  above the claim, above the provisional credit limit, or above what the ledger supports (`CaseFacts.creditCeiling`:
  the disputed posting, or for an amount mismatch the posted amount above the authorised one), whatever the claim
  says. Other refusals:
  - a non-decline outside the time limit;
  - an outcome the category's policy never reaches (no credit for goods not received, no merchant evidence for an
    unauthorised transaction or a duplicate);
  - merchant evidence for goods not received before the 15-day wait after the expected delivery date has passed;
  - evidence that is not the case's, and unknown or missing policy sections;
  - an action the outcome does not allow, and invalid action arguments.

  Limits come from `PolicyLimits` (120 days, 3,000.00 EUR and 15 days by default, as in the policy corpus).
- `investigation`: `DisputeCase` (received, triaged, investigating, decided or escalated; only legal transitions,
  each recorded), `EscalationReason` (a guard failure counts as an unsafe proposal), `LoopBudget` and `LoopUsage`
  (caps on model calls, tool calls, tokens and wall clock).

Pure Java, no clock: time is passed in. Parsing the model's JSON into a `Decision` belongs to an adapter.

## Application (M1)

- `HandleDisputeOpened`: one dispute from the event to a decided or escalated case: triage, then investigation.
- `TriageDispute`: the cheap model classifies the statement as structured output; on a refusal, an invalid answer
  or an unreachable model, the recorded category is used and the fallback audited. A disagreement reaches the
  investigator as both categories.
- `InvestigateDispute`: the loop (ADR 0002). Before the loop the investigator itself, not the model, collects the
  records of the dispute's card (`CaseEvidence`): every posting and refund on the card, its authorisations and the
  dispute's documents. The guard accepts citations from that set and policy sections the model actually retrieved.
  `CaseEvidence` also reads, for the guard only, the posted and authorised amounts of the disputed transaction and the
  expected delivery date of the merchant's order confirmation (a date the cardholder wrote does not count). The model sees only the ledger and policy tools (`Toolbox`), strict and sorted, with a
  stable cached system prompt (`system-prompt.md`) and the case in the user turn. Stop reason first: `refusal`,
  truncation and anything unexpected escalate; a turn's tool calls run concurrently and return in one message,
  wrapped as untrusted, failures as `is_error`. An answer that fails the decision schema is sent back once with
  the errors. Every model call, tool call (arguments as SHA-256), state change and decision is audited.
- Skills (`Skills`): the system prompt ends with the skill index; the local `load_skill` tool returns a skill's
  procedure, audited as `SkillLoaded`. No MCP server may offer a tool of that name.
- Ports: `LlmPort`, `McpToolPort`, `JsonPort` (the application may not use a JSON library), `AuditLog`,
  `SkillLibrary`.

`adapters/json/JacksonJsonAdapter` implements `JsonPort` with Jackson 3 and `json-schema-validator` (the MCP SDK's
validator), so the decision is validated against `decision.schema.json` before it is mapped.
`adapters/skills/FileSkillLibrary` reads `skills/*/SKILL.md`.

`adapters/anthropic/AnthropicAdapter` implements `LlmPort` on the Anthropic Java SDK (non-beta Messages API, one
non-streaming call per request). `AnthropicMapping` is the only class that knows the request shape: adaptive thinking
sent explicitly, `output_config` with the effort and the JSON schema format, strict tools, the cache breakpoint on
the system block, assistant turns passed back block for block; never `tool_choice`, sampling parameters or a
thinking budget. Responses carry a cost from `PriceTable` (configured in `application.yml`, versioned). SDK
exceptions become `LlmCallException` kinds (rate limited, overloaded, timeout, server error, bad request,
authentication); the SDK does not retry (`max-retries: 0`): retries live in one place, around the port.

`adapters/resilience/ResilientLlmPort` (ADR 0008) wraps every model adapter, replay included, outermost first:
retry (3 attempts, exponential backoff with jitter), circuit breaker, bulkhead (8 concurrent calls), time limiter
(4 minutes per attempt, the attempt cancelled at the limit). Settings are under `investigator.resilience`.

- **Transient kinds** (rate limited, overloaded, server error, timeout, connection) are retried and count against
  the breaker.
- **Permanent kinds** (bad request, authentication, fixture missing) are neither retried nor counted.
- **An open breaker or a full bulkhead** is a new kind, `UNAVAILABLE`.

When a retryable kind survives the retries, or the ledger cannot be read before the loop, the case is not escalated:
it is parked as `AWAITING_RETRY`. It is stored with its `disputes.opened` event (Flyway `V4`) to be replayed from the
start, and nothing is emitted yet. A configuration error in the tool servers (`Toolbox.InvalidToolset`) still
escalates.

The replay (task 2.10, `application/replay`):
- A parked case is due at `next_attempt_at` (Flyway `V5`): 30 s after it parks, the wait doubling with each replay
  up to 10 minutes.
- `SweepParkedCases` runs every 10 s. While the model is available, it sends each due case's `disputes.opened` event
  to `disputes.replay` through the outbox, and holds the case for 15 minutes while the replay runs.
- The listener re-runs the case from the start (`HandleDisputeOpened.resume`). An outcome completes it; parking again
  waits longer. A replay for a case no longer parked is ignored.
- After 5 replays, a case that parks again escalates as `DEPENDENCY_UNAVAILABLE`.
- "Available" asks the circuit breaker for a permit rather than reading its state: an open breaker moves to half-open
  only when something tries a call, so a sweep that only read the state would wait forever once no new disputes
  arrive.

Observability (task 2.11, `adapters/otel`, ADR 0008): decorators on Micrometer's vendor-neutral API. The bootstrap
connects them to OpenTelemetry (Boot's starter: the Micrometer Tracing bridge and OTLP, no Java agent) and to
Prometheus.

- **Traces:** one trace per case. The listener (and the eval runner) opens `dispute.case`; children are a span per
  model attempt (`ObservedLlmPort`, inside the retries: model, effort, stop reason, failure kind, tokens, cost), per
  tool call (`ObservedMcpToolPort`: server, tool, outcome, a hash of the arguments) and per action
  (`ObservedActionPort`).
- **Context across threads:** both virtual-thread executors (tool calls, model attempts) carry the case's context.
- **OTLP export** is off unless `OTEL_TRACES_EXPORT=true` and an endpoint are set (compose, task 2.12).
- **Metrics** on `/actuator/prometheus`: `MeteredAuditLog` derives them from the audit trail:
  - cases by outcome and reason, unsafe proposals, parked and replayed cases;
  - tokens by model and type (the cache hit ratio is `cache_read` over `input` plus `cache_read`), cost;
  - tokens and cost per case run (histograms), approval latency.

  The observations add `dispute_llm_call_seconds` and `dispute_tool_call_seconds` (latency, and the error rate by
  `outcome`). Gauges cover outbox unsent rows and lag, parked cases, and the breaker.
- **Logs:** JSON (ECS) on stdout, with the dispute id, trace id and span id on every line. `MaskingLogCustomizer`
  masks anything shaped like a card number (Luhn-checked) in every string. `LOG_FORMAT=` (empty) gives plain text.

Dead letters: a malformed event is never retried, and an event that still fails after the error handler's two retries
goes to `disputes.dlq`. Spring's headers record why (`kafka_dlt-exception-message`) and where it came from, and the
partition moves on.

`adapters/fixtures` (ADR 0006): `Fixtures` keys a request by the SHA-256 of its canonical JSON and stores
`evals/fixtures/llm/<key>.json` (canonical request plus response, no timestamps); `RecordingAdapter` writes them
around the live adapter; `ReplayAdapter` serves them with no client and fails with `FIXTURE_MISSING` on a miss.
`InvestigationReplayTest` records a whole investigation and replays it with fresh instances.

`adapters/mcpclient/McpClientAdapter` implements `McpToolPort` on the MCP Java SDK's Streamable HTTP client
(ADR 0003): one client per configured server, connected on first use; `tools/list` read once, every page; every
discovered schema checked against the strict subset (a server that drifts fails discovery, not the model call);
request timeout and maximum response size; a transport failure drops the client so the next call reconnects. Only
the servers it is constructed with exist: the bootstrap gives it the ledger and policy servers, never actions.
`McpClientAdapterTest` runs against an in-process server on the SDK's servlet transport; `McpStackTest`
(`MCP_STACK_TEST=1`, compose stack running) checks the client against ledger-mcp and the Python policy-mcp.

`adapters/kafka`: `DisputeEvents` (the versioned JSON of `disputes.opened`, `disputes.decided` and
`disputes.approval-requested`),
`DisputeOpenedListener` (one record per poll, acknowledged once the case and its outgoing events are committed; a
malformed event is logged and skipped), `OutboxRelay` (ADR 0008: claims unsent outbox rows with
`FOR UPDATE SKIP LOCKED`, sends them in id order keyed by dispute id, marks each one sent; a failed send records the
error and ends the batch, and the next poll, every `OUTBOX_POLL_INTERVAL`, default 200 ms, tries again).
`adapters/persistence`: `JdbcCaseStore` and `JdbcAuditLog` on `JdbcClient` in the `investigation` schema (Flyway
`V1`, `V2` for approval, `V3` the outbox), owned by the `investigator` role, which cannot read the ledger, intake or
policy schemas. A case is inserted when its investigation ends, with its outgoing events in `investigation.outbox`,
in one transaction, and then updated, each update guarded by the state the writer read
(`WHERE state = :expected`), so two reviewers cannot both act; a stored case is restored through
`DisputeCase.restore`. Plain JDBC, not JPA: two tables and one guarded update need no ORM.

`application/approval` (ADR 0005): `RequestApproval` puts a decided case's action before a person before the case is
first saved; `ApproveAction` signs the token through `TokenSigner`, stores the case `APPROVED` with it, then calls
`ActionPort` and records `ACTED` or `ACTION_FAILED` (approving an interrupted case again resends the same token);
`RejectAction` ends it `REJECTED`.

`adapters/web`: `ReviewController`, the reviewer API on port 8080, every call with the `X-Reviewer-Key` header
(`REVIEWER_API_KEY`; blank answers 503):

```sh
curl -H 'X-Reviewer-Key: local-reviewer-key' localhost:8080/reviews                     # the queue
curl -X POST -H 'X-Reviewer-Key: local-reviewer-key' -H 'Content-Type: application/json' \
     -d '{"reviewer":"me","note":"evidence checked"}' localhost:8080/reviews/DSP-000018/approve  # or /reject
```

200 says where the case ended (`ACTED`, `ACTION_FAILED`, `REJECTED`); 404 unknown case; 409 not waiting (another
reviewer was first); 400 no reviewer; 401 wrong key; 503 no reviewer key, or no signing key for approvals.
`adapters/approval/McpActionPort` calls actions-mcp through its own MCP client, which knows only that server and
is given to nothing else: the investigation's client, and so the model, never reaches a write.

`adapters/approval` ([docs/approval-token.md](../docs/approval-token.md)): `Ed25519TokenSigner` signs the token as a
compact JWS with the JDK's Ed25519, header and payload in JCS (`Jcs`, RFC 8785), so tokens are deterministic;
`ApprovalKeys` reads the base64 keys and derives the key id. The signer refuses a private and public key that are not
a pair. Its bean exists when `investigator.approval.signing-key` is set; compose mounts both keys as a Spring config
tree at `/run/secrets/approval` (`APPROVAL_KEYS_DIR`). `ApprovalTokenVectorsTest` checks the signer and JCS against
`docs/approval-token/vectors.json` and checks every verdict there with a reference verifier.

`bootstrap`: `InvestigatorProperties` binds `investigator.*`; `InvestigatorConfiguration` wires adapters to ports and
use cases. `LLM_ADAPTER` picks `replay` (default, no key), `recording` or `anthropic`. `ProcessDisputeOpened` is
idempotent on the dispute id: a stored case is published again, never investigated again.
`InvestigatorEndToEndTest` runs the service on Testcontainers Kafka and PostgreSQL (the compose images and init
scripts) with a scripted model and canned tools: record a dispute, redeliver it, then replay it in a second instance
with no live call.

Profile `eval` (`--spring.profiles.active=eval`): no Kafka, database or web server; `EvalRunner` runs the golden
cases through the same `HandleDisputeOpened` and writes one result per case for the Python graders (see
`evals/README.md`). `ServiceConfiguration` holds what only the service needs; `InvestigatorConfiguration` what both
share.

Live check (off by default, never in CI, under one cent):
`ANTHROPIC_LIVE_TEST=1 ANTHROPIC_API_KEY=... ./mvnw -pl adapters test -Dtest=AnthropicLiveTest`

## Status

M1 done: the service runs one dispute category end to end on Kafka and PostgreSQL, and the eval profile replays
the golden set. M2 in progress: the approval gate works end to end (2.3 to 2.7): a decided case waits for a
person, the reviewer API approves or rejects it, and an approved action runs once on actions-mcp with a signed token.
Every event leaves through the outbox (2.8). The model call is wrapped in Resilience4j, and a case the model or
the ledger cannot serve is parked instead of escalated (2.9). Parked cases are replayed and poison events
dead-lettered (2.10). Traces, metrics and JSON logs come from decorators
around the ports (2.11). Next: Prometheus, Tempo and Grafana in compose (2.12).
