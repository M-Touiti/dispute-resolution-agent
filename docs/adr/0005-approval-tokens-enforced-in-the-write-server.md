# ADR 0005: Approval tokens enforced inside the write server

- Status: accepted
- Date: 2026-11-23
- Deciders: repository owner

## Context

From M2 the investigator can act: provisional credit, a merchant evidence request, closing a dispute. The agent only
proposes; a person approves; then the action runs on `actions-mcp`, the only server that writes (ADR 0003). The model
never receives the write tools: they are called by the investigator's own code, after approval.

That leaves one question: what stops a call to `actions-mcp` that no person approved? A bug in the investigator, a
compromised investigator, or a replayed request would all look, to the write server, like an ordinary MCP call. If
the server trusts its caller, the approval gate is only as strong as every line of the caller. The tools already
require an `approval_token` argument (M0); this ADR fixes what the token is and who checks it.

## Decision

**The write server verifies every call itself.** `actions-mcp` executes a tool only with a token it has verified, for
exactly this dispute, this tool and these arguments, used once. It trusts nothing else about the caller.

**Signature: Ed25519, with the JDK.** The investigator holds the private key and signs a token when a reviewer
approves; `actions-mcp` holds only public keys and verifies. A compromised write server cannot mint tokens, and only
the holder of the private key can. Java has Ed25519 in `java.security` (`KeyPairGenerator`, `Signature` with
`"Ed25519"`) since 15, so neither side needs a library.

**Format: compact JWS** (RFC 7515), `base64url(header).base64url(payload).base64url(signature)`, no padding.

- Header: `{"alg":"EdDSA","kid":"<key id>","typ":"approval+jwt"}`. The key id is the first 16 hex characters of the
  SHA-256 of the raw public key.
- Payload: `{"v":1,"jti":"<UUID>","sub":"<dispute id>","act":"<tool name>","args":"<hex SHA-256>","rev":"<reviewer
  id>","iat":<epoch seconds>,"exp":<epoch seconds>}`.
- `args` is the SHA-256 of the tool's arguments without `approval_token`, canonicalised as JCS (RFC 8785): keys sorted,
  no whitespace, UTF-8. The arguments are strings, integers and arrays of strings, so canonicalisation stays small.
- The token lives for five minutes. The investigator calls the action right after signing, so the expiry only bounds
  the use of a leaked token.

Any JOSE library can read the token; neither side depends on one. `docs/approval-token.md` documents the format, and
`docs/approval-token/vectors.json` holds shared test vectors (keys, arguments, tokens, expected verdicts) that both
Java projects' tests read, so the signer and the verifier cannot drift apart silently.

**Verification order in `actions-mcp`**, each step rejecting with a reason and changing nothing:

1. The token parses, `alg` is `EdDSA`, `typ` is `approval+jwt`, `v` is 1, and `kid` names a configured public key.
2. The signature verifies.
3. `iat` is not in the future and `exp` has not passed (30 seconds of clock skew allowed).
4. `act` equals the tool being called and `sub` equals the call's `dispute_id`.
5. `args` equals the hash the server computes from the call's own arguments.
6. Business limits hold on the server's own reading of the data: a credit at most the disputed amount, a dispute not
   already closed.
7. Single use: in one transaction, the server inserts the `jti` into `actions.approval_tokens` and performs the
   write. If the `jti` already exists, the call is a retry of the same approved action (the arguments are bound by
   the signature): the server returns the stored result and writes nothing.

A rejection is an MCP tool result with `isError: true` and `APPROVAL_REJECTED: <reason>`, the M0 contract. The
`tools/list` descriptions already promise all of this and do not change.

**The token id is the idempotency key.** The investigator stores the token with the approval and re-sends the same
token when it retries a call whose answer it lost, so a retry never writes twice and never needs a new approval.

**Keys.** The private key is configuration of the investigator (`APPROVAL_SIGNING_KEY`, PKCS#8, base64), the public
keys configuration of `actions-mcp` (`APPROVAL_VERIFY_KEYS`, X.509, base64, a list for rotation). In compose a
one-shot init service generates the pair into a volume on first start; in Kubernetes they are secrets. No key is
committed. Rotation: add the new public key to the server, switch the signer, remove the old key after the expiry.

**Storage.** `actions-mcp` gets its own schema, `actions`, and database role, `actions_service`: it writes the
token uses and the actions there and reads the disputes it checks limits against. It cannot read or write the
investigation schema; the investigator cannot write the actions schema.

**Audit.** The investigator records the approval request, the decision (reviewer, note), the token issued (its `jti`,
never the token), and the action's result. `actions-mcp` records every accepted call with its `jti`; rejections are
logged with the reason.

## Consequences

- The approval gate holds even if the investigator is wrong or compromised, as long as the private key is safe: a
  call without a fresh, matching, unused token changes nothing.
- One action needs one approval. Changing an argument after approval (a different amount) needs a new approval,
  because the hash no longer matches.
- Two implementations of the token rules exist, the signer and the verifier, in separate projects. The shared
  vectors are what keeps them equal; a format change bumps `v` and both sides together.
- A leaked private key defeats the gate for everything it signs until rotation. The key never leaves the
  investigator's configuration, and the five-minute expiry limits how long a leaked token stays useful.
- The investigator must know the arguments exactly as it sends them; it builds the arguments once, hashes them and
  sends the same object.
- Reviewers are identified by the reviewer API (a static API key stands in for SSO in M2); `rev` records who
  approved, not proof of who it was.

## Alternatives considered

- **HMAC with a shared secret.** Simpler, but the write server would hold the signing secret, so a compromised write
  server could mint its own approvals. Rejected.
- **A JWT library (for example Nimbus JOSE).** Standard and well tested, but a dependency in both projects for one
  algorithm the JDK provides. The token stays a standard JWS, so a library can still read it.
- **mTLS between the investigator and `actions-mcp`.** Authenticates the caller, not the approval: a compromised or
  buggy investigator could still call any action. Worth adding for transport, not a replacement.
- **The write server reads the approval from the investigator's database.** Couples the write server to the
  investigation schema and needs read access across the boundary this design exists to keep.
- **An authorisation server with OAuth token exchange.** The production shape at scale, and too much machinery for
  this deployment. The JWS format keeps the move open.
