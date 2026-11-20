# investigator

Java 21, Spring Boot 4.1.2, Maven multi-module (hexagonal):

- `domain`: entities, value objects, case state machine. No dependencies outside the JDK; a Maven enforcer rule
  fails the build otherwise.
- `application`: ports (`LlmPort`, `McpToolPort`, repositories, clock) and use cases (triage, investigate, guard,
  act). Depends on `domain` only, also enforced.
- `adapters`: kafka, jpa, anthropic, mcp-client, otel. The only module that sees SDK types.
- `bootstrap`: the Spring Boot application, configuration, wiring.

Base package `dev.disputeagent.investigator`. SDK versions are pinned in the parent `pom.xml` (see
`docs/ROADMAP.md`).

## Build

From this directory (the wrapper pins Maven 3.9.12; on Windows use `mvnw.cmd`):

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

## Status

M1 in progress: domain (1.7), use cases (1.8) and skills (1.9) done; the LLM, MCP, Kafka and JPA adapters (1.10 to
1.13) follow.
