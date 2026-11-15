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

## Status

M0: module layout and the port interfaces with their provider-neutral types, no adapter implementations. The agent
loop and the adapters land in M1.
