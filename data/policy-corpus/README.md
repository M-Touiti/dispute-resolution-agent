# Policy corpus

The card dispute policy the agent reads through `policy-mcp`. Self-written plain English, modelled on the public
structure of card-network dispute rules (reason categories, evidence, time limits) without copying any network's
text or codes. Nothing here is derived from any employer.

The rules and numbers must match `data/datagen/policy.py`, which the ground truth is generated from;
`data/tests/test_policy_corpus.py` checks the numbers, the actions and the structure. Change the two together,
regenerate the data, and re-record the evaluation fixtures (ADR 0001): the corpus is part of every prompt.

## Files

| File | Applies to |
|---|---|
| `general.md` | All categories: scope, the order the rules apply in, time limits, provisional credit, decisions and actions, evidence, untrusted content, escalation |
| `unauthorised-transaction.md` | `UNAUTHORISED_TRANSACTION` |
| `duplicate-charge.md` | `DUPLICATE_CHARGE` |
| `goods-not-received.md` | `GOODS_NOT_RECEIVED` |
| `cancelled-recurring.md` | `CANCELLED_RECURRING` |
| `amount-mismatch.md` | `AMOUNT_MISMATCH` |

## Format

Each file has one `#` title and any number of sections. A section is a `##` heading followed by a metadata line,
then the text:

```markdown
## Time limit for opening a dispute

Section `time-limits`; version 1; applies to: all categories

A cardholder must open a dispute ...
```

- **Section id:** lowercase words joined by hyphens, unique across the corpus, never reused or renamed. Decisions
  cite it, so renaming one breaks recorded decisions.
- **Version:** a positive integer, raised when the section's meaning changes.
- **Applies to:** `all categories`, or a comma-separated list of category names as in `datagen/model.py`.
- **Text:** ends at the next `##` heading or the end of the file. Numbers in the text are limited to the policy
  values (a day count, the credit limit in EUR, the tip percentage) so that the consistency test can check
  every one of them.
