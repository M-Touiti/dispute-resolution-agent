# Diagrams

Mermaid sources for the architecture and sequence diagrams referenced from the top-level README.

| Diagram | Shows |
|---|---|
| [investigation-loop.md](investigation-loop.md) | One dispute from `disputes.opened` to `disputes.decided`: triage, case evidence, the tool loop, schema retry, the policy guard, audit and acknowledgement (M1) |
| [approval-and-parking.md](approval-and-parking.md) | What M2 added around it: a decided case through the reviewer API to a token-verified write on actions-mcp, and a case the model cannot serve parked, swept and replayed (M2) |
