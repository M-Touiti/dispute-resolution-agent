# ADR 0002: Manual agent loop versus the SDK tool runner

- Status: accepted
- Date: 2026-11-19
- Deciders: repository owner

## Context

The investigator runs one agent loop per dispute: send the conversation to Claude, execute the tools Claude asks
for, return the results, repeat until Claude produces a decision. Something has to own that loop.

The Anthropic SDKs ship a tool runner (beta in every SDK, including Java as `BetaToolRunner`) that runs it: it
calls the API, executes tool functions, feeds results back, and stops when Claude stops calling tools. Anthropic's
guidance is to default to it. The runner is not a black box: it yields each assistant message before the tools
run, so a caller can inspect, deny or replace a tool call, modify results, and bound the loop with a maximum
number of iterations. Approval gates, logging and interception therefore do **not** by themselves justify writing
the loop by hand, and this ADR does not rely on them.

What this project needs that the runner does not fit:

1. **The model call must go through `LlmPort`** (ADR 0001). The recording and replay adapters sit behind that
   port; CI and the load test never reach the API. The runner makes the API call itself through the SDK client,
   so with the runner the replay adapter would have to move down to the HTTP layer, and SDK types would reach the
   application layer, which the module rules forbid.
2. **Tools are discovered at runtime** from MCP servers (ADR 0003). The Java runner is built around tool classes
   declared in code; our tool set is a list of JSON schemas fetched at start-up.
3. **Every exit is a case state transition.** A refusal, a cap (tool calls, model calls, tokens, wall clock), a
   decision that fails schema validation twice, or a guard failure escalates the case with a reason that is
   audited and counted. These are the loop's control flow, not hooks around it.
4. **One audit row per model call and per tool call**, with tokens, cost from the price table, latency, the
   model that answered and the outcome, written in order with the case state.
5. **The stable, non-beta request path.** Everything the loop needs (strict tools, structured output, effort,
   prompt caching) is on the generally available Messages API.

## Decision

The loop is written by hand in the `application` module, over `LlmPort` and `McpToolPort`, with no SDK types. Its
contract:

- Send; check `stop_reason` before reading content. `end_turn` with a valid decision ends the loop; `tool_use`
  continues; `refusal` escalates; `max_tokens` and `model_context_window_exceeded` escalate with the reason;
  `pause_turn` is not expected (no server tools) and escalates if it appears.
- Append the assistant message exactly as returned, thinking blocks and their signatures included, so the next
  request carries them unchanged.
- Execute the tool calls of one turn concurrently and return all results in one user message, each with its
  `tool_use_id`; a failed call returns `is_error: true` with the reason, never a silent drop. Results are wrapped
  in delimiters and labelled untrusted.
- Enforce the caps before each call; a cap ends the loop with an escalation, never a truncated decision.
- Validate the final decision against its JSON schema; on failure, retry once with the validation errors, then
  escalate.
- Write an audit record for every model call and tool call.

## Consequences

- The loop is unit-testable with fake ports: no key, no network, no SDK. The replay adapter covers the rest.
- We own the details the runner would handle: tool-use and tool-result pairing, thinking-block preservation,
  stop reasons, concurrency of parallel calls. Tests in the `application` module pin each one.
- A new API behaviour (a new stop reason, a new content block) reaches the loop only through the port's types;
  the adapter maps it, the loop decides what it means. Unknown stop reasons escalate.
- The loop is roughly two hundred lines that the runner would have provided. Accepted: it is the part of the
  system a reader most wants to see, and the evaluation measures it.

## Alternatives considered

- **SDK tool runner (`BetaToolRunner`) with per-turn interception.** Covers gating, logging and result
  modification. Rejected for reasons 1 and 2 above: it calls the API directly, which bypasses the replay adapter,
  and it expects tools as code. Would be the right choice for an agent without record and replay, or with tools
  known at compile time.
- **Managed Agents.** Anthropic runs the loop and hosts the tool sandbox. Rejected: the tools live on private
  servers next to the ledger, the loop is the subject of the evaluation, and CI must run without the API.
- **Spring AI `ChatClient` with tool callbacks.** Rejected for the reasons in ADR 0001 and ADR 0003: it hides the
  Messages API features the project uses and couples the MCP client to Spring AI.
