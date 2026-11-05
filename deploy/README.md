# Deployment

- `compose/` - `docker compose up` runs PostgreSQL (pgvector), Kafka (KRaft), the three MCP servers, the
  investigator, Prometheus and Grafana (M0 brings the first three; the rest in M1 and M2)
- `k8s/` - Kustomize base and overlays, HPA, ServiceMonitor, optional Argo CD application (M2)
