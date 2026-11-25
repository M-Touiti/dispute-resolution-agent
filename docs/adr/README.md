# Architecture decision records

Format: Michael Nygard's template (Context, Decision, Consequences), one file per decision, never edited after
acceptance; a superseding decision gets a new number and links back.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-scope-and-provider-port.md) | Scope and the LLM provider port | accepted |
| [0002](0002-manual-agent-loop.md) | Manual agent loop versus the SDK tool runner | accepted |
| [0003](0003-self-hosted-mcp-servers-and-client.md) | Self-hosted MCP servers and client versus the Messages API MCP connector | accepted |
| [0004](0004-local-embedding-model.md) | Local sentence-embedding model behind a port | accepted; model choice superseded by 0007 |
| [0005](0005-approval-tokens-enforced-in-the-write-server.md) | Approval tokens enforced inside the write server | accepted |
| [0006](0006-recorded-fixtures-and-nightly-live-evaluation.md) | Recorded fixtures for CI and nightly live evaluation | accepted |
| [0007](0007-embedding-model-and-fusion-measured-on-the-corpus.md) | Embedding model and fusion weights measured on the policy corpus | accepted |
| [0008](0008-reliability-and-observability-as-port-decorators.md) | Reliability and observability as decorators around the ports | accepted |
| [0009](0009-evaluation-design.md) | Evaluation design: golden set v2, metrics, model-graded rubric, nightly slice | accepted; refines 0006 |
