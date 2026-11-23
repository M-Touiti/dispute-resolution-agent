# The approval token

The format behind [ADR 0005](adr/0005-approval-tokens-enforced-in-the-write-server.md). The investigator signs one
token when a person approves an action; `actions-mcp` verifies it on every call and does nothing without it. This
page is the contract between the two. [`approval-token/vectors.json`](approval-token/vectors.json) pins it: both
projects' tests read those vectors, so a change here changes both sides in the same pull request.

## Shape

A compact JWS (RFC 7515): `base64url(header) "." base64url(payload) "." base64url(signature)`, base64url without
padding. A token containing `=` is malformed.

Header and payload are UTF-8 JSON serialised with JCS (below), so the same claims and key always give the same token
(Ed25519 signatures are deterministic).

```json
{"alg":"EdDSA","kid":"1db0a4de7ff81f1c","typ":"approval+jwt"}
```

| Payload claim | Type | Meaning |
|---|---|---|
| `v` | integer | Format version, `1`. A new version changes both sides together |
| `jti` | string | Token id, a UUID. Single use; also the idempotency key of the action |
| `sub` | string | The dispute id the action is for |
| `act` | string | The `actions-mcp` tool: `issue_provisional_credit`, `request_merchant_evidence` or `close_dispute` |
| `args` | string | Lower-case hex SHA-256 of the tool's arguments without `approval_token`, canonicalised with JCS |
| `rev` | string | The reviewer id the reviewer API was given: who approved, not proof of it |
| `iat` | integer | Issued at, epoch seconds |
| `exp` | integer | Expiry, epoch seconds: `iat + 300` |

## Signature

Ed25519 (RFC 8032) over the ASCII bytes of `base64url(header) "." base64url(payload)`. Java's `Signature` with
`"Ed25519"` on both sides; no library.

Keys:
- The private key is the JDK's PKCS#8 encoding, base64.
- The public key is its X.509 SubjectPublicKeyInfo, base64.
- The key id is the first 16 hex characters of the SHA-256 of the raw 32-byte public key: the last 32 bytes of the
  X.509 encoding, which always starts `302a300506032b6570032100`.

## Canonical arguments (JCS, RFC 8785)

The hash covers the arguments exactly as sent, minus `approval_token`. JCS, for the subset the tools use:

- Object members sorted by name, comparing **UTF-16 code units** (Java's `String.compareTo`, JavaScript's default
  `sort`). Not code points: Python's `sort_keys` orders `😀` after `！`, JCS before.
- Strings escaped as JavaScript's `JSON.stringify` does: `\"`, `\\`, `\b \f \n \r \t`, other control characters as
  `\u00xx` in lower case; everything else as is, including `/` and non-ASCII.
- Integers within ±2^53 as plain decimals. Fractions, larger integers and lone surrogates are refused rather than
  canonicalised differently on the two sides.
- No whitespace; UTF-8 bytes.

The arguments per tool, as the investigator sends them:

| Tool | Arguments besides `approval_token` |
|---|---|
| `issue_provisional_credit` | `dispute_id`, `amount_minor`, `currency` |
| `request_merchant_evidence` | `dispute_id`, `evidence` (array of strings), `note` |
| `close_dispute` | `dispute_id`, `reason`, `policy_section_ids` (array of strings) |

## Verification in `actions-mcp`

In this order. The first failure rejects the call with `APPROVAL_REJECTED: <reason>` and changes nothing:

| # | Check | Reason |
|---|---|---|
| 1 | Three segments, base64url without padding, JSON header and payload, every claim present | `MALFORMED` |
| 2 | `alg` is `EdDSA`, `typ` is `approval+jwt`, `v` is 1 | `UNSUPPORTED` |
| 3 | `kid` names a configured public key | `UNKNOWN_KEY` |
| 4 | The signature verifies with that key | `BAD_SIGNATURE` |
| 5 | `iat <= now + 30 s` | `NOT_YET_VALID` |
| 6 | `now < exp + 30 s` | `EXPIRED` |
| 7 | `act` is the tool being called | `WRONG_TOOL` |
| 8 | `sub` is the call's `dispute_id` | `WRONG_DISPUTE` |
| 9 | `args` equals the hash of the call's own arguments | `ARGUMENTS_MISMATCH` |
| 10 | Business limits on the server's own data (task 2.6) | `LIMIT_EXCEEDED`, `DISPUTE_CLOSED` |
| 11 | Single use: `jti` recorded in the write's transaction. A known `jti` returns the stored result and writes nothing | none |

Checks 1 to 9 are pure and pinned by the vectors. Checks 10 and 11 need the server's database and have their own
tests (task 2.6).

## Keys in each deployment

| Where | Signing side (investigator) | Verifying side (actions-mcp) |
|---|---|---|
| Compose | `approval-signing` volume at `/run/secrets/approval`, files `investigator.approval.signing-key` and `investigator.approval.verify-key` (a Spring config tree) | `approval-verify` volume: `actions-mcp.approval.verify-keys` only |
| Kubernetes (task 2.15) | A secret mounted the same way | A secret with the public keys only |
| Tests | `vectors.json` holds test keys; they sign nothing else | Same file |

The one-shot `approval-keys` service generates the pair on the first start, with the JDK alone
(`deploy/compose/approval-keys/GenerateApprovalKeys.java`). It keeps the pair after that. `docker compose down -v`
deletes it, and the next start makes a new one.

Rotation:
1. Add the new public key to `actions-mcp`; it accepts a list.
2. Switch the investigator's signing key.
3. Remove the old public key once the last token signed with it has expired, five minutes later.

## Changing the format

1. Change the signer and `ApprovalTokenVectorsTest` in `investigator/adapters`.
2. Regenerate: `./mvnw -pl adapters test -Dtest=ApprovalTokenVectorsTest -Dvectors.update=true` from `investigator/`.
   The test keys are kept.
3. Review the diff of `vectors.json`, change the `actions-mcp` verifier to match, and bump `v` if old tokens must no
   longer verify.
