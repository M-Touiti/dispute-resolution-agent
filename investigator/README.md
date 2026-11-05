# investigator

Java 21, Spring Boot 4.1.2, Maven multi-module (hexagonal):

- `domain` - entities, value objects, case state machine; no framework dependencies
- `application` - ports (`LlmPort`, `McpToolPort`, repositories, clock) and use cases (triage, investigate, guard, act)
- `adapters` - kafka, jpa, anthropic, mcp-client, otel
- `bootstrap` - the Spring Boot application, configuration, wiring

Skeleton lands in M0 task 0.5; the loop in M1.
