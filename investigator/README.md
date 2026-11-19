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

## Status

M1 in progress: the domain above is done (task 1.7); the use cases (1.8) and the adapters (1.10 to 1.13) follow.
