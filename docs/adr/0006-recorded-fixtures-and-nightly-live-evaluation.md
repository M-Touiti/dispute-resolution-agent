# ADR 0006: Recorded fixtures for CI and nightly live evaluation

- Status: accepted
- Date: 2026-11-20
- Deciders: repository owner

## Context

The investigator calls a model on every case. Pull-request CI must check the whole loop (the prompt, the tools, the
guard, the graders) on every change, without an API key, without spend, and with the same result on every run. The
load test needs the same: measure the platform, not the model. But the model is the part that drifts: a prompt, a
tool schema, a model id, an effort setting or a tool result changes what it does.

ADR 0001 put the model behind `LlmPort` with three adapters: live, recording and replay. This ADR fixes how fixtures
are keyed, stored and kept honest, and how the live model is still checked.

## Decision

**Key.** A fixture is keyed by the SHA-256 of the canonical request: the port request (model id, effort, token cap,
system blocks with their cache breakpoint, every message block including thinking signatures and tool results, tool
definitions, output schema, and a format version) as JSON with every object's keys sorted, embedded JSON (tool
inputs and schemas) parsed and sorted too. The key ignores formatting and key order and nothing else. The port request
holds no timestamps and no secrets by construction; the case's own dates are case data and belong in it.

**Storage.** `evals/fixtures/llm/<key>.json`, committed: the format version, the key, the canonical request in full and
the response (id, answering model, stop reason, refusal category, usage, content). The canonical request is stored so
that a pull request shows what changed in the prompt. Tool inputs are stored as their exact text, so replay hands the
same bytes to the tools. No recording time: the same response re-recorded writes the same bytes.

**Replay.** `ReplayAdapter` has no client and no key. A request without a fixture fails with `FIXTURE_MISSING` (not
retryable), naming the key and the file; the case escalates and the graders fail. It never falls back to a live
call. Cost is recomputed from the current price table, so a price change needs no re-recording.

**Recording.** `RecordingAdapter` wraps the live adapter and writes one fixture per call; a failed call writes nothing.
Only the recording session uses it, with an explicit go-ahead and the Console spend cap set.

**What a miss means.** A change to the system prompt, a skill, a tool description or schema, the model id, the effort,
the token cap, the decision schema, or any tool result the model reads changes a request and misses. That is the
enforcement of ADR 0001's re-baseline rule: the pull request that makes the change re-records the affected fixtures.
Data that only the investigator reads (the evidence index for the guard) does not enter the model's requests; the
guard always runs on the current data.

**Nightly live evaluation (M3).** A scheduled workflow runs a rotating 30-case slice of the golden set against the
live model with a hard spend cap: the Console's monthly limit, plus a per-run budget the runner checks against the
computed cost of each call before the next case. Results are committed to a dated file in `evals/nightly/`. Before a
tag, the full golden set runs live and the report is regenerated.

## Consequences

- CI tests the real loop end to end (prompt assembly, tool calls against the seeded servers, parsing, guard, graders)
  with no key, no spend and no flakiness from the model.
- A replayed run only proves that the code still produces the recorded requests and handles the recorded answers.
  It says nothing about whether the model would still answer the same way; the nightly slice and the pre-tag run do.
- Fixtures make prompt changes visible and costly: every change shows in the diff and needs a recording session.
  That is the intent.
- Fixtures are large (the system prompt is repeated in every request) but plain JSON and reviewable; at 30 cases
  and a handful of turns each, a few megabytes.
- The model's answers are bound to its thinking signatures. Replay returns them unchanged, so the next request is
  identical to the recorded one; a fixture recorded on one model cannot be replayed for another (the model id is in
  the key).

## Alternatives considered

- **Mock the SDK or the HTTP layer.** Ties the tests to the SDK's request shape and hides the prompt from review.
  Rejected; the port is the seam (ADR 0001).
- **Key on the SDK's serialised request body.** Would make every SDK upgrade, even a cosmetic one, re-record every
  fixture. The port request is what the investigator decides; the adapter's mapping has its own tests.
- **Fuzzy matching (nearest fixture on a miss).** Would let a changed prompt replay an old answer: the failure this
  design exists to prevent.
- **Live calls in pull-request CI.** Spend, flakiness and a secret in every fork's workflow. Rejected.
