# Local stack (docker compose)

| Service | Image | Host port | Inside the network |
|---|---|---|---|
| `postgres` | `pgvector/pgvector:0.8.7-pg18-trixie` | `127.0.0.1:5432` | `postgres:5432`, database `disputes` |
| `kafka` | `apache/kafka:4.2.2`, single-node KRaft | `127.0.0.1:9092` | `kafka:19092` |
| `ledger-mcp` | built from `mcp-servers/ledger-mcp` | `127.0.0.1:8081` | `http://ledger-mcp:8081/mcp` |
| `policy-mcp` | built from `mcp-servers/policy-mcp` | `127.0.0.1:8082` | `http://policy-mcp:8082/mcp` |
| `actions-mcp` | built from `mcp-servers/actions-mcp` | `127.0.0.1:8083` | `http://actions-mcp:8083/mcp` |

The host ports match `.env.example`, so the investigator can run on the host against the stack. The investigator,
Prometheus and Grafana join in M1 and M2.

## Run

```sh
(cd ../../data && uv run python generate.py)   # once; PostgreSQL loads data/generated/seed.sql on first start
docker compose up -d --build --wait            # builds the three servers, waits until everything is healthy
./smoke.sh                                     # seed loaded, Kafka round trip, MCP handshake and tools/list
docker compose down                            # stop; add -v to drop the database volume
```

- **Seed:** `postgres/initdb/` runs only on an empty volume. It installs pgvector, then loads `seed.sql`, and fails
  if the file is missing. After regenerating the data, run `docker compose down -v` and start again. The
  healthcheck uses TCP, so `postgres` reports healthy only after the seed has loaded. `ground_truth.csv` is mounted
  read-only with the rest of `data/generated/` but never loaded.
- **Kafka:** topic auto-creation is off; topics are created explicitly from M1.
- **policy-mcp:** DNS rebinding protection is on, so `POLICY_MCP_ALLOWED_HOSTS` lists the service name as well as
  localhost.
- **Images:** multi-stage builds, run as a non-root user (uid 10001), and tests are skipped in the image build
  because CI runs them. Database credentials default to `disputes`/`disputes`; override them with `POSTGRES_USER`
  and `POSTGRES_PASSWORD`.
- **Windows:** run `smoke.sh` from Git Bash. It runs container commands through `sh -c` so Git Bash does not
  rewrite their paths.
