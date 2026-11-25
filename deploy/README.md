# Deployment

- `compose/` - `docker compose up` runs PostgreSQL (pgvector), Kafka (KRaft), the three MCP servers, the
  investigator, Prometheus and Grafana (M0 brings the first three; the rest in M1 and M2)
- `k8s/` - Kustomize base and a `local` overlay for kind (`kind/up.sh`, `kind/check.sh`), HPA, network policies,
  ServiceMonitors, optional Argo CD application; see `k8s/README.md`
