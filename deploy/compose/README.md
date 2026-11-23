# Local stack (docker compose)

| Service | Image | Host port | Inside the network |
|---|---|---|---|
| `postgres` | `pgvector/pgvector:0.8.7-pg18-trixie` | `127.0.0.1:5432` | `postgres:5432`, database `disputes` |
| `kafka` | `apache/kafka:4.4.0`, single-node KRaft | `127.0.0.1:9092` | `kafka:19092` |
| `ledger-mcp` | built from `mcp-servers/ledger-mcp` | `127.0.0.1:8081` | `http://ledger-mcp:8081/mcp` |
| `policy-mcp` | built from `mcp-servers/policy-mcp` | `127.0.0.1:8082` | `http://policy-mcp:8082/mcp` |
| `actions-mcp` | built from `mcp-servers/actions-mcp` | `127.0.0.1:8083` | `http://actions-mcp:8083/mcp` |
| `investigator` | built from `investigator` | `127.0.0.1:8080` (health, reviewer API) | consumes `disputes.opened` and `disputes.replay`; publishes `disputes.decided`, `disputes.approval-requested`, `disputes.replay` (through its outbox) and `disputes.dlq` (dead letters) |
| `approval-keys` | `eclipse-temurin:21-jdk-noble`, runs once | none | generates the approval-token key pair on the first start into `approval-signing` (investigator) and `approval-verify` (public key only, for actions-mcp); see [docs/approval-token.md](../../docs/approval-token.md) |

| `prometheus` | `prom/prometheus:v3.16.0` | `127.0.0.1:9090` | scrapes the investigator, ledger-mcp and actions-mcp (`/actuator/prometheus`) and policy-mcp (`/metrics`) every 5 s |
| `tempo` | `grafana/tempo:2.10.10` | `127.0.0.1:3200` | receives the investigator's spans over OTLP/HTTP at `tempo:4318`; traces on a tmpfs, kept while the stack runs |
| `grafana` | `grafana/grafana:13.3.1` | `127.0.0.1:3000` | the **Dispute agent** dashboard and the Prometheus and Tempo datasources, provisioned from `observability/grafana` |

The host ports match `.env.example`, so the investigator can also run on the host against the stack.

The `perf` profile adds `load-shim` (port 8090) and `k6` for the load test; `performance/run.sh` starts them (see
`performance/README.md`). Prometheus finds investigator replicas by DNS, so a scaled investigator is scraped whole.

## Observability (ADR 0008)

Open http://localhost:3000. The home dashboard is **Dispute agent**, read-only without a login; log in as
`admin`/`admin` (`GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`) to explore. Its rows:

- cases by outcome, unsafe proposals, parked cases and replays;
- model latency and failures, the circuit breaker, the prompt cache hit ratio, tokens;
- cost per case and spend;
- tool error ratio and policy-mcp latency;
- approval latency, actions, outbox lag, scrape targets;
- recent case traces from Tempo.

Click a trace to see the case span with its model, tool and action spans.

The dashboard is `observability/grafana/dashboards/dispute-agent.json`: change it there, as the UI does not save
changes. Every service's metrics carry an `application` tag, and every investigator log line its trace id and
dispute id. `smoke.sh` checks that every scrape target is up, that the case's trace is in Tempo with its model and
tool spans, and that Grafana has both datasources and the dashboard.

## Run

```sh
(cd ../../data && uv run python generate.py)   # once; PostgreSQL loads data/generated/seed.sql on first start
docker compose up -d --build --wait            # builds the three servers, waits until everything is healthy
./smoke.sh                                     # seed, Kafka, MCP handshakes and reads, one dispute end to end
docker compose down                            # stop; add -v to drop the database volume
```

- **Seed:** `postgres/initdb/` runs only on an empty volume. It installs pgvector, then loads `seed.sql`, and fails
  if the file is missing. After regenerating the data, run `docker compose down -v` and start again. The
  healthcheck uses TCP, so `postgres` reports healthy only after the seed has loaded. `ground_truth.csv` is mounted
  read-only with the rest of `data/generated/` but never loaded.
- **Roles:** `postgres/initdb/20-ledger-reader.sh` creates `ledger_reader`, which `ledger-mcp` uses: `SELECT` on
  the `ledger` schema only, read-only transactions. Its password defaults to `ledger_reader`; override it with
  `LEDGER_READER_PASSWORD`. `ledger-mcp` starts after `postgres` is healthy and its readiness includes the database.
  `30-policy-service.sh` creates `policy_service`, which owns the `policy` schema only; `policy-mcp` ingests the
  corpus there at start-up (mounted read-only from `data/policy-corpus`). Its password defaults to
  `policy_service`; override it with `POLICY_SERVICE_PASSWORD`. `50-actions-service.sh` creates `actions_service`,
  which owns the `actions` schema (actions-mcp migrates it) and may read `intake.disputes` and nothing else;
  override its password with `ACTIONS_SERVICE_PASSWORD`. After pulling this script, run `docker compose down -v`:
  init scripts run only on an empty volume.
  `40-investigator.sh` creates `investigator`, which owns the `investigation` schema (cases and audit, migrated by
  Flyway) and nothing else; the investigator joins the stack in M1 task 1.14.
- **Investigator:** `LLM_ADAPTER` selects `replay` (default: serves `evals/fixtures/llm`, needs no key), `recording`
  or `anthropic` (both need `ANTHROPIC_API_KEY` in the environment). `skills/` and `evals/fixtures/llm/` are mounted
  from the repository. Until the fixtures are recorded (M1 task 1.16), replay misses on every call and each case
  escalates with `DEPENDENCY_UNAVAILABLE`; the audit row names the missing fixture. The smoke test accepts either
  outcome: it checks the pipeline (event in, evidence read from ledger-mcp, case and audit stored, event out). Set
  `SMOKE_DISPUTE` to send another dispute; a dispute already stored is published again, not investigated again.
  On Linux, recording through compose needs the fixtures folder writable by uid 10001; recording from the host
  (`./mvnw -pl bootstrap spring-boot:run` with `LLM_ADAPTER=recording`) avoids that.
- **Kafka:** topic auto-creation is off; topics are created explicitly from M1.
- **policy-mcp:** DNS rebinding protection is on, so `POLICY_MCP_ALLOWED_HOSTS` lists the service name as well as
  localhost.
- **Images:** multi-stage builds, run as a non-root user (uid 10001), and tests are skipped in the image build
  because CI runs them. Database credentials default to `disputes`/`disputes`; override them with `POSTGRES_USER`
  and `POSTGRES_PASSWORD`.
- **Windows:** run `smoke.sh` from Git Bash. It runs container commands through `sh -c` so Git Bash does not
  rewrite their paths.
