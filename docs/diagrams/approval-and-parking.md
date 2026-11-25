# The approval gate and parked cases (M2)

What M2 added around the [investigation loop](investigation-loop.md): a decided case waits for a person, and only
an approval lets the write server act (ADR 0005); a case the model or a server cannot serve is parked and replayed
rather than escalated (ADR 0008). Every event leaves through the outbox, written in the same transaction as the case.

## Approval

The model never sees the write tools. The investigator signs a token only after a person approves, and actions-mcp
holds the public key alone: it can check a token but never mint one.

```mermaid
sequenceDiagram
    autonumber
    participant I as investigator
    participant DB as PostgreSQL<br/>(investigation)
    participant K as Kafka
    actor R as reviewer
    participant A as actions-mcp
    participant ADB as PostgreSQL<br/>(actions, intake)

    Note over I: the loop ends DECIDED with one proposed action<br/>(issue_provisional_credit, request_merchant_evidence, close_dispute)
    I->>DB: case AWAITING_APPROVAL + outbox rows, one transaction
    DB-->>K: outbox relay: disputes.decided, disputes.approval-requested
    R->>I: GET /reviews (X-Reviewer-Key)
    I-->>R: pending cases with their proposed action
    alt reject
        R->>I: POST /reviews/{id}/reject (reviewer, note)
        I->>DB: REJECTED, audited; actions-mcp is never called
    else approve
        R->>I: POST /reviews/{id}/approve (reviewer, note)
        Note over I: token claims: case, action, SHA-256 of the canonical arguments (JCS),<br/>token id, reviewer, issued and expiry (5 min); signed Ed25519, compact JWS
        I->>DB: APPROVED with the token, guarded by the state read<br/>(a second reviewer gets 409; a crash resumes with the stored token)
        I->>A: tools/call with approval_token (MCP client that knows actions-mcp only)
        Note over A: signature against the public key, expiry, binding:<br/>the argument hash recomputed from this call
        A->>ADB: lock the dispute; token id already used?
        alt same token, same arguments
            ADB-->>A: the stored result (idempotent retry, no second write)
        else same token, other arguments
            A-->>I: rejected
        else new token
            Note over A: business limits from intake.disputes: credit at most the<br/>disputed amount (cumulative), dispute not already closed
            A->>ADB: the write and the token's use, one transaction
        end
        A-->>I: result or the reason it was refused
        I->>DB: ACTED or ACTION_FAILED with the server's answer, audited
        I-->>R: 200 with the final state
    end
```

An unreachable actions-mcp ends the case `ACTION_FAILED` with "outcome unknown": whether the write ran is then a
person's question, and approving again resends the same token, which the server answers from its record.

## Parking and replay

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant I as investigator
    participant RL as Resilience4j<br/>(around LlmPort)
    participant C as Claude
    participant DB as PostgreSQL<br/>(investigation)

    K->>I: disputes.opened
    alt malformed, or failing past the error handler's retries
        I->>K: disputes.dlq with the reason in headers (the partition moves on)
    else
        I->>RL: model call
        RL->>C: attempt (time limiter), bulkhead on concurrent calls
        Note over RL,C: transient kinds (rate limited, overloaded, server error, timeout):<br/>retry with jittered backoff, counted by the circuit breaker
        alt answered
            C-->>RL: response
            RL-->>I: response: the loop goes on as in M1
        else still failing after the retries, or the breaker is open (UNAVAILABLE)
            RL-->>I: retryable failure
            I->>DB: AWAITING_RETRY with the original event and next_attempt_at
            Note over I: also parked: a server that cannot be reached before the loop.<br/>Permanent kinds (fixture miss, authentication, invalid request) escalate at once
        end
        I->>K: acknowledge (after the save)
    end

    loop every 10 s, in every replica
        I->>DB: parked cases that are due
        alt replayed max-replays (5) times already
            I->>DB: ESCALATED DEPENDENCY_UNAVAILABLE + disputes.decided in the outbox
        else the breaker grants a permit
            I->>DB: hold the case in flight; disputes.replay in the outbox
            DB-->>K: outbox relay: disputes.replay
            K->>I: disputes.replay: the case re-runs from the start
            Note over I: decided or escalated: completed as usual.<br/>Parked again: the wait doubles (30 s, 60 s, ... up to 10 min)
        else breaker open
            Note over I: held back until the next sweep
        end
    end
```

Measured under load with the replay adapter (`performance/README.md`, task 2.14): with the model forced to answer
"overloaded" for 40 seconds, all 30 cases in flight parked once and were decided on their first replay, within 25
seconds of the fault's end, with their outcomes unchanged.
