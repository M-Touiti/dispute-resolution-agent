# The investigation loop (M1)

One `disputes.opened` event to one `disputes.decided` event. The loop is hand-written over `LlmPort` (ADR 0002);
the model only reaches tools through the investigator, which routes each call to an allowlisted MCP server
(ADR 0003). The actions server is not reachable in M1: every outcome is a proposal.

Since M2 the end of this diagram is different: the case and its events are saved in one transaction and an outbox
relay publishes them, a decided case with an action waits for a reviewer, and a model or server outage parks the case
instead of escalating it. See [approval-and-parking.md](approval-and-parking.md).

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant I as investigator
    participant DB as PostgreSQL<br/>(investigation)
    participant H as Claude Haiku<br/>(triage)
    participant O as Claude Opus<br/>(investigation)
    participant L as ledger-mcp
    participant P as policy-mcp

    K->>I: disputes.opened
    I->>DB: case already stored?
    alt redelivery of a finished case
        I->>K: disputes.decided (stored outcome, again)
    else new case
        I->>H: classify (strict tool schema)
        H-->>I: category
        Note over I: disagrees with the event? use the event's category,<br/>audit the fallback, tell the investigator both

        I->>L: case evidence (transaction, card's authorisations and transactions, card, cardholder, merchant)
        L-->>I: records: the only ids the decision may cite
        Note over I: records contradict the dispute: escalate CASE_DATA_INVALID

        loop until a decision or a cap (model calls, tool calls, tokens, wall-clock time)
            I->>O: stable system prompt (cache breakpoint), skill index, tools from MCP, conversation
            O-->>I: response
            alt stop_reason = tool_use
                par every tool call of the turn, on virtual threads
                    I->>L: get_authorisations, search_transactions, ...
                    L-->>I: result
                and
                    I->>P: search_policy, get_policy_section
                    P-->>I: result
                and
                    Note over I: load_skill: SKILL.md from skills/
                end
                Note over I: results wrapped as untrusted data,<br/>all of them in one user message (is_error on failures)
            else stop_reason = end_turn
                Note over I: decision against the JSON schema,<br/>invalid once: sent back with the errors, twice: escalate
            else refusal, max_tokens, anything else
                Note over I: escalate MODEL_REFUSED / UNEXPECTED_STOP
            end
        end

        Note over I: PolicyGuard: time limit, evidence and policy cited, cited evidence from the case,<br/>cited sections exist, one action, allowed for the outcome, credit within<br/>the disputed amount and the limit. Fails: escalate, unsafe proposal
        Note over I: every step above is an audit row: state changes, model calls<br/>with tokens and cost, tool calls, skills, rejections, the guard result
        I->>DB: save case, outcome and audit rows
        I->>K: disputes.decided (DECIDED, or ESCALATED with the reason)
        Note over I,K: acknowledged after the save, a crash before it re-runs the case,<br/>after it only re-publishes
    end
```

In evaluation (`--spring.profiles.active=eval`) the same `HandleDisputeOpened` runs without Kafka and PostgreSQL:
disputes come from `data/generated`, audit rows stay in memory, and each case becomes a result file for the graders.
With `LLM_ADAPTER=replay` the two Claude participants are served from `evals/fixtures/llm` (ADR 0006).
