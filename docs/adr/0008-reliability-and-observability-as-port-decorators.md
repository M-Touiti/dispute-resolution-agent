# ADR 0008: Reliability and observability as decorators around the ports

- Status: accepted
- Date: 2026-11-23
- Deciders: repository owner

## Context

M1 treats the model and the tool servers as always there. The SDK retries twice, and anything that still fails
escalates the case as `DEPENDENCY_UNAVAILABLE`, which ends it. The Kafka consumer acknowledges an event only after the
case is saved *and* its outcome published, so a broker hiccup on the publish holds up the partition. A malformed
event is logged and skipped. The audit log is the only record of what happened: there are no traces or metrics.

M2 has to make the model call behave like any external API (time limit, bounded retry, a cap on concurrency, a
circuit breaker), lose no case and no event, and make one case visible end to end. The constraint is ADR 0001's
architecture: `application` holds the loop and the ports and depends on nothing but `domain` (the build enforces
it), and the evaluation replays the loop bit for bit (ADR 0006).

## Decision

**Decorators around the ports, in `adapters`.** Each concern is an implementation of the port that wraps another
one. The bootstrap module composes them; `application` neither knows nor changes.

```
LlmPort       = Resilient( Observed( Anthropic | Recording | Replay ) )
McpToolPort   = Observed( McpClientAdapter )
AuditLog      = Observed( Jdbc | Eval )
```

**Resilience (`adapters/resilience`), Resilience4j around `LlmPort`**, outermost to innermost: retry, circuit
breaker, bulkhead, time limiter. Each attempt passes the breaker and the bulkhead, and each attempt is timed.

- Retry: exponential backoff with jitter, only on retryable kinds (`RATE_LIMITED`, `OVERLOADED`, `SERVER_ERROR`,
  `TIMEOUT`, `CONNECTION`). The SDK's `max-retries` goes to 0: retries live in one place.
- Circuit breaker on the same kinds; permanent kinds (`BAD_REQUEST`, `AUTHENTICATION`, `FIXTURE_MISSING`) neither
  retry nor count against it.
- Bulkhead: a cap on concurrent model calls across all cases, to stay inside the rate limit; a call waits briefly
  for a slot, then fails as retryable.
- Time limiter: each attempt runs on a virtual thread and is cancelled at the limit.
- An open breaker or a full bulkhead surfaces as `LlmCallException` with a new retryable kind, `UNAVAILABLE`, so the
  application still sees provider-neutral kinds only.

**A case the model cannot serve is parked, not escalated.** When a model call fails with a retryable kind after the
retries, or the ledger cannot be read before the loop, the case moves to `AWAITING_RETRY` with its `disputes.opened`
event stored and a replay count. Permanent kinds still escalate at once, so a missing fixture fails CI fast. A tool
error *inside* the loop is unchanged: it goes back to the model as `is_error` (ADR 0003), because the model can work
around one failed lookup.

**Replay.** A scheduled sweep picks parked cases that are due (exponential backoff on the replay count) and, while the
breaker is not open, publishes their event to `disputes.replay` through the outbox. The listener consumes
`disputes.opened` and `disputes.replay` alike. A parked case is re-run from scratch: the loop keeps no state between
attempts, and in replay the fixtures make a re-run free and identical. After five replays the case escalates as
`DEPENDENCY_UNAVAILABLE`, which is then a real outage, not a blip.

**Dead letters.** A malformed event, and an event whose processing still fails after the error handler's retries,
goes to `disputes.dlq` with headers for the reason, the source topic, partition and offset. The partition moves on.

**Outbox.** Every event the investigator emits (`disputes.decided`, `disputes.approval-requested`, `disputes.replay`) is
a row in `investigation.outbox`, written in the transaction that saves the case. A poller publishes pending rows in
id order and marks them sent; it claims rows with `FOR UPDATE SKIP LOCKED`, so several replicas can poll without
sending a row twice in the normal case. Delivery is at least once and consumers are idempotent on the dispute id
(the rule since M1). The listener acknowledges once the case and its outbox rows are committed; the publish is off
the consumer's path. Order is kept per poller, not across replicas; no consumer depends on order across disputes.

**Observability (`adapters/otel`), Micrometer Observation and Tracing** with the OpenTelemetry bridge and the OTLP
exporter that Spring Boot manages; no Java agent.

- One trace per case. The Kafka listener, and the evaluation runner, open the case span; every other span is a
  child: triage, each model call (model id, effort, stop reason, input, output, cache read and cache creation
  tokens, cost), each tool call (server, tool, argument hash, outcome; never the arguments), the guard, the approval
  wait, each action. A retried model call shows one span per attempt.
- Prometheus metrics from the same observations and the audit events: cases by outcome and escalation reason,
  tokens and cost per case (histograms), model latency, tool error rate by server and tool, unsafe proposals,
  parked cases and replays, approval latency, cache hit ratio, outbox lag.
- Logs are JSON, keyed by case id and trace id, with anything shaped like a card number masked before it is written,
  although the data is synthetic.

**Evaluation is unaffected.** The eval profile composes the same decorators. Replay never fails with a retryable kind,
so resilience is a pass-through there, and the fixtures still see the exact same requests.

## Consequences

- The loop, the guard and the use cases stay framework-free and testable with fakes; each decorator is tested with
  a fake port and a fake clock, without Spring.
- An outage of the model or the ledger delays cases instead of failing them, and the delay is bounded and visible
  (the parked-cases gauge, the replay counter, then an escalation).
- A re-run repeats model calls that had already succeeded before the failure. With the live model that costs tokens
  again; parking only follows retries that already failed, so it is rare.
- The outbox adds a table, a poller and a delay of up to one poll interval to every event.
- Three more Kafka topics (`disputes.replay`, `disputes.dlq`, `disputes.approval-requested`) and their configuration.
- Tracing without the agent means libraries the agent would have instrumented (JDBC, the Kafka client) are covered
  only where Micrometer instruments them; the spans that matter (model, tool, guard, approval) are explicit.

## Alternatives considered

- **Resilience4j annotations on the application's use cases.** Puts Spring and Resilience4j into `application`,
  which the build forbids, and hides the policy in annotations. Rejected.
- **The SDK's own retries only.** No breaker, no bulkhead, no parking: an outage still ends every case in flight.
- **Escalate instead of parking.** Simple, and wrong for an outage: every case in flight would need a person.
- **Retry inside the Kafka error handler (seek back).** Blocks the partition for the length of the outage, and the
  backoff is per record, not per dependency.
- **Change data capture (Debezium) for the outbox.** The production choice at scale; one more service and connector
  for one table here. The table shape keeps the move open.
- **The OpenTelemetry Java agent.** Broad automatic instrumentation for free, but it is absent from the build and the
  tests, attaches at start-up, and does not know about tokens or cost; the manual spans are the point.
