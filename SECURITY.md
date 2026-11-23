# Security

## Provenance

Everything in this repository is synthetic or self-written. No schema, policy text, code name, vendor, dataset or
configuration is derived from any employer, past or present. The policy corpus is written in plain English and is
modelled on the public *structure* of card-network dispute rules (reason categories, evidence requirements, time
limits) without copying any network's text or codes.

## Reporting a vulnerability

Open a private security advisory on GitHub, or email the maintainer listed in the repository profile. Please do not
open a public issue for a vulnerability.

## What is in scope

- The approval-token scheme enforced inside `actions-mcp` (signature, single use, binding to case, action and
  argument hash, expiry).
- The policy guard that validates every decision deterministically.
- Prompt-injection defences around untrusted content (dispute text, merchant data, policy text).
- Secrets handling: environment variables locally, Kubernetes secrets in the manifests, nothing in the repository.
  The approval-token keys are generated on the first start of the compose stack and never committed. The keys in
  `docs/approval-token/vectors.json` are test keys, published on purpose so both implementations can check the same
  vectors; they sign nothing outside tests.

## Automated checks

CodeQL (Java, Python and the GitHub Actions workflows) and Dependabot (Maven, uv, Docker, compose and Actions)
run on every pull request and weekly. Container image scanning is added in M2.
