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
- `guard`: `PolicyGuard` checks every decision deterministically and reports every broken rule: a credit above
  the disputed amount or above the provisional credit limit, a non-decline outside the time limit, evidence that
  is not the case's, unknown or missing policy sections, an action the outcome does not allow, invalid action
  arguments. Limits come from `PolicyLimits` (120 days, 3,000.00 EUR by default, as in the policy corpus).
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
  records of the dispute's card (`CaseEvidence`); the guard accepts citations from that set and policy sections the
  model actually retrieved. The model sees only the ledger and policy tools (`Toolbox`), strict and sorted, with a
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
authentication); retries live in the SDK (`max-retries`) until Resilience4j takes over in M2.

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
`DisputeOpenedListener` (one record per poll, acknowledged after the case is stored and its outcome published; a
malformed event is logged and skipped), `KafkaDecisionPublisher` (both outgoing topics, keyed by dispute id, waits
for the broker).
`adapters/persistence`: `JdbcCaseStore` and `JdbcAuditLog` on `JdbcClient` in the `investigation` schema (Flyway
`V1`, `V2` for approval), owned by the `investigator` role, which cannot read the ledger, intake or policy schemas.
A case is inserted when its investigation ends and then updated, each update guarded by the state the writer read
(`WHERE state = :expected`), so two reviewers cannot both act; a stored case is restored through
`DisputeCase.restore`. Plain JDBC, not JPA: two tables and one guarded update need no ORM.

`application/approval` (ADR 0005): `RequestApproval` puts a decided case's action before a person before the case is
first saved; `ApproveAction` signs the token through `TokenSigner`, stores the case `APPROVED` with it, then calls
`ActionPort` and records `ACTED` or `ACTION_FAILED` (approving an interrupted case again resends the same token);
`RejectAction` ends it `REJECTED`. Approve and reject are wired once the signer (2.5), `actions-mcp` (2.6) and the
reviewer API (2.7) exist.

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
the golden set. M2 in progress: a decided case is stored `AWAITING_APPROVAL` and raises
`disputes.approval-requested` (2.4); approving it needs the token signer (2.5), `actions-mcp` verification (2.6) and
the reviewer API (2.7). Parking arrives with resilience (2.9).
