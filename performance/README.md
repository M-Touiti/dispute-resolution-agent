# Load testing

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
| `shim/` | FastAPI and aiokafka (Python 3.13, uv): `POST /disputes`, `GET /summary?since=&until=`, `GET /health`; tests: `uv run pytest` |
| `k6/disputes.js` | the k6 scenario: constant arrival rate, `dispute_e2e` trend, counters for turned-away and timed-out disputes |
| `run.sh` | one run, as above |
| `compose.replicas.yml` | the override for several replicas |
| `results/` | run summaries, git-ignored |

Failure injection (task 2.14) builds on the same shim.
