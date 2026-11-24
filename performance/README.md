# Load and failure testing

How much the investigator handles, and what changes with more replicas. k6 drives a small HTTP shim that publishes
`disputes.opened` and answers when the matching `disputes.decided` arrives. The model is the **replay adapter**: the
recorded answers of the golden set, so a run spends nothing (task 2.13, ADR 0008).

## What is measured

| Number | Where it comes from |
|---|---|
| cases per second | the shim: decisions in the window divided by first send to last decision |
| end-to-end latency (p50, p95, p99, max) | the shim: `disputes.opened` published to `disputes.decided` received, so the case's processing, its tool calls over MCP, the database and the outbox relay |
| consumer lag | Kafka: the `investigator` group's committed offsets against the end of `disputes.opened`, sampled every 0.5 s |
| database connections | `pg_stat_activity` per role, sampled every 0.1 s (a 5 s Prometheus scrape misses connections busy for milliseconds) |
| outbox lag | the `outbox` table: `sent_at - created_at` of the events written during the run |

## Results (23 Nov 2026)

60 s at a constant arrival rate after a 20 s warm-up, on the compose stack: one laptop (16 logical cores, Docker
Desktop on Windows 11), the investigator with its default settings (one listener thread, a pool of 5 connections per
replica), `disputes.opened` with 3 partitions.

| | 1 replica, 5/s | **1 replica, 20/s** (before) | **3 replicas, 20/s** (after) | 3 replicas, 30/s |
|---|---|---|---|---|
| decided / sent | 301 / 301 | 782 / 782 | 1201 / 1201 | 1658 / 1658 |
| arrivals turned away (all 30 disputes in flight) | 0 | 419 | 0 | 143 |
| cases per second | 5.0 | **12.6** (saturated) | **19.9** (keeps up) | 27.5 (saturated) |
| e2e p50 | 216 ms | 2017 ms | 230 ms | 837 ms |
| e2e p99 | 331 ms | **4117 ms** | **771 ms** | 1494 ms |
| e2e max | 362 ms | 4596 ms | 918 ms | 1753 ms |
| consumer lag, max | 1 | 28 | 10 | 27 |
| outbox lag p99 | 210 ms | 238 ms | 193 ms | 192 ms |
| investigator connections open / most active at once | 5 / 2 | 5 / 1 | 15 / 3 | 15 / 4 |
| ledger / policy / actions connections, most active | 1 / 1 / 0 | 1 / 1 / 0 | 2 / 1 / 0 | 2 / 1 / 0 |
| outcomes | 241 DECIDED, 60 ESCALATED | 635 / 147 | 961 / 240 | 1328 / 330 |

Each run's full summary is written to `results/` (git-ignored); the table copies those four.

**Before and after.** At 20 disputes a second, one replica saturates at about 12.6 cases a second. The backlog then
fills the 30 disputes the test can send, so p99 reaches 4.1 s and 419 arrivals are turned away. Three replicas carry
the same load: p99 drops to 0.77 s, nothing is turned away, and the lag peaks at 10. The topic's 3 partitions are the
ceiling: a fourth replica would sit idle.

**What else the runs show:**

- **Load does not change the outcome.** Every run decides 80% and escalates 20%, the golden set's own split (24 and
  6). Under load the replay answers stay the same, and no case was lost or decided twice.
- **The outbox relay sets the floor.** It polls every 200 ms (`OUTBOX_POLL_INTERVAL`), so about 200 ms of a quiet
  case's 216 ms p50 is waiting for the relay. Lowering it buys latency with more polling queries.
- **The database is not the limit.** Under the heaviest load no role had more than 4 connections busy at once. The
  investigator's pools (5 per replica) never ran out. Every replica holds its pool open, which is what replicas cost
  PostgreSQL.
- **Three replicas give about 2.2 times one, not 3.** At 30 a second the 30-dispute ceiling turns arrivals away
  (lag 27 of 30), so 27.5 is a lower bound. The MCP servers, PostgreSQL and Kafka are single instances on the same
  laptop.

## Failure injection (23 Nov 2026)

One investigator replica, 1 dispute a second for 150 s, and one fault 30 s into the run (`faults.sh`, task 2.14).
Each run is cut at the fault: disputes sent before it, during it and after it.

| | model overloaded for 40 s | policy-mcp paused for 40 s | 5 malformed events |
|---|---|---|---|
| decided / sent | 127 / 127 | 141 / 141 | 150 / 150 |
| sent during the fault: p50 / max | 49.5 s / 61.8 s | 27.6 s / 40.6 s | 268 ms / 443 ms |
| sent after the fault: p99 | 415 ms | 2358 ms | 464 ms |
| every dispute sent before the fault's end decided after | 24.9 s | 3.4 s | 0.1 s |
| parked at most | 30 | 0 | 0 |
| consumer lag, max | 21 | 30 | 5 |
| arrivals turned away (all 30 disputes in flight) | 23 | 10 | 0 |
| disputes whose outcome changed | none | 1 (see below) | none |

**The model overloaded.** The fault switch makes the replay adapter answer `OVERLOADED` for 40 s.
- **Fast failure.** Only 10 attempts reached the model (6 triage, 4 investigation). The circuit breaker then opened,
  and later calls failed at once without waiting out their retries.
- **Every case in flight parked:** 30 cases, each once. The sweep replayed each after its first 30 s wait, and each
  was decided on that first replay.
- **Recovery.** Every dispute sent before the fault's end was decided within 25 s of it, and parked cases were back
  to zero after 24 s. Every outcome matched the dispute's other runs, so a park and replay leaves the answer unchanged.

**A stalled policy-mcp.** The container is paused, so calls to it hang rather than fail.
- **Bounded, but blocking.** The MCP request timeout (30 s) bounds the wait. But one listener thread per replica runs
  one case at a time, so the case waiting on the stalled server held up every case behind it. Lag reached the
  30-dispute ceiling and 10 arrivals were turned away.
- **One escalation.** DSP-000070 was mid-investigation. Its policy call timed out and went back to the model as an
  error, as ADR 0008 decides for a tool error inside the loop. On replay there is no recording of that turn, so the
  case escalated `DEPENDENCY_UNAVAILABLE` (a missing fixture). Against the live model, the model would see the error
  and could retry the search or escalate. DSP-000070 escalates in the golden set anyway, for its own reason
  (`AGENT_DECISION`).
- **Recovery.** 3.4 s after the unpause, everything sent before was decided.
- **Levers.** If stalls matter, two would shorten the blocking: more listener threads per replica, or a shorter
  timeout for policy-mcp than for the ledger.

**Malformed events.** Five events that are not JSON, published among the disputes: all five reached
`disputes.dlq` with their reason. The partition moved on, so the disputes around them were not slowed: p99 was
447, 443 and 464 ms before, during and after. Nothing else changed.

**Unchanged in every run:** no decision was lost, and no case was decided twice.

## Caveats

- **The model is replayed.** A recorded answer comes back in microseconds; a real call takes seconds to tens of
  seconds and costs money. These numbers are the system around the model: Kafka, MCP, PostgreSQL, the outbox. In
  production the model's latency and the API's rate limits set the throughput, and one listener thread per replica
  would hold a case for the whole investigation. Before going live, concurrency per replica has to grow, or the
  number of replicas, with the partition count to match.
- **One laptop.** Every service, k6 and the shim share the same cores, so the numbers are relative (one replica
  against three), not capacity planning.
- **Thirty disputes.** Only the golden set has recorded answers, so the test re-sends the same 30 disputes. A
  dispute is resent only after its decision arrives, which caps the work in flight at 30. When that cap is reached,
  arrivals are turned away and counted instead of queueing in Kafka. Before each send the shim deletes the
  dispute's case; that reset is not part of the latency.
- **No approvals.** Cases that propose an action wait for approval; the load test measures the investigation, not
  the reviewer API or actions-mcp.
- **Faults on replay.** The fault switch fakes the API's overloaded answer; it does not reproduce its timing. A real
  outage also slows the calls that do succeed. A tool error inside the loop has no recording, so on replay such a
  case escalates where the live model would carry on.

## Run it

Needs Docker and `data/generated` (`uv run python generate.py` in `data/`). The stack must be startable as in
`deploy/compose/README.md`. From the repository root, in Git Bash on Windows:

```sh
REPLICAS=1 RATE=20 DURATION=60s bash performance/run.sh    # before
REPLICAS=3 RATE=20 DURATION=60s bash performance/run.sh    # after
```

`run.sh` starts the stack with the `perf` compose profile and that many investigator replicas. A replica count
above one uses `compose.replicas.yml`, which drops the fixed host port 8080. The script then runs a warm-up at
1 per second (`WARMUP`, in seconds, not counted) and k6 at `RATE` for `DURATION`. Last, it writes the shim's summary
to `results/replicas-<n>-rate-<r>.json`. It refuses any adapter but replay.

k6 fails the run only when a decision is lost (no `disputes.decided` within 120 s). Saturation is a result, not a
failure. Watch the run in Grafana (http://localhost:3000): Prometheus finds every replica by DNS. After running
with several replicas, `docker compose up -d` in `deploy/compose` brings the investigator's port 8080 back.
The load test deletes and re-runs the golden cases, hundreds of times each. Before running `smoke.sh` again, reset
the stack with `docker compose down -v`.

| Part | What it is |
|---|---|
| `shim/` | FastAPI and aiokafka (Python 3.13, uv): `POST /disputes`, `GET /summary?since=&until=[&fault_start=&fault_end=]`, `POST /faults/malformed`, `GET /dlq`, `GET /health`; tests: `uv run pytest` |
| `k6/disputes.js` | the k6 scenario: constant arrival rate, `dispute_e2e` trend, counters for turned-away and timed-out disputes |
| `run.sh` | one load run, as above |
| `faults.sh` | one failure-injection run |
| `compose.replicas.yml` | the override for several replicas |
| `results/` | run summaries, git-ignored |

### Failure injection

```sh
bash performance/faults.sh llm-overloaded    # also policy-stall, malformed
```

`faults.sh` starts the stack with one replica and `FAULT_INJECTION=true`, and runs k6 at `RATE` (default 1) for
`DURATION` (default 150s). It injects the fault at `FAULT_AT` seconds (default 30) for `FAULT_FOR` seconds (default
40), then writes `results/fault-<scenario>.json`. It fails if a decision is lost, or if a malformed event misses the
dead-letter topic. Requests wait up to 600 s for a parked case's replay. When it ends, it unpauses policy-mcp and
restarts the investigator with the switch off.

The fault switch is `/faults` on the investigator:
- `POST /faults/llm-overloaded?seconds=N`, `DELETE /faults/llm-overloaded`, `GET /faults`;
- it exists only with `FAULT_INJECTION=true`, and the investigator refuses to start with that setting on any
  adapter but replay;
- `smoke.sh` checks that it is absent on a normal stack.

Malformed events come from the shim (`POST /faults/malformed?count=N`); `GET /dlq` counts the dead letters.
