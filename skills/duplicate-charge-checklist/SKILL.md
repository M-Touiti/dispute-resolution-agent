---
name: duplicate-charge-checklist
description: The step-by-step evidence check for a dispute where the cardholder says one purchase was charged more than once, in the order the policy applies, with the outcome each finding leads to. Load it for every duplicate-charge case.
---

# Duplicate charge: evidence checklist

Work through the steps in order and stop at the first that decides the case. Each step names the policy section to
read and cite; retrieve it with `search_policy` or `get_policy_section` before citing it.

1. **Get the disputed transaction** with `get_transaction`. Note its `posted_on` date, `transaction_at`,
   `amount_minor`, `merchant_id` and `auth_id`. Check it is on the card named in the case.

2. **Time limit** (`time-limits`). Count the days from `posted_on` to the date the dispute was opened. More than
   the limit: **decline**, citing `time-limits`.

3. **Find the other posting and any refund** with `search_transactions` on the card, filtered by the disputed
   transaction's `merchant_id`, over a few days around its date. Look for a second purchase of the same amount and
   for a `REFUND` whose `related_transaction_id` is one of the two postings.

4. **Decide on what the ledger shows:**
   - No second posting of the same amount at that merchant: nothing was charged twice. **Escalate**, citing
     `escalation`, and say that the records show one posting.
   - A refund of the extra posting exists: nothing remains in dispute. **Decline**, citing
     `duplicate-already-refunded`, and cite the disputed posting and the refund.
   - The two postings carry the same `auth_id`: one authorisation was posted twice. Go to step 5. Cite both postings
     and the authorisation (read it with `get_authorisations` by `auth_id`).
   - The two postings carry different `auth_id` values: two purchases, each authorised. Read both authorisations
     with `get_authorisations`. **Decline**, citing `duplicate-separate-authorisations`, and cite both postings and
     both authorisations.

5. **Provisional credit** (`provisional-credit`). The credit is the amount of one posting, the extra one: never
   both postings, whatever the claimed amount says. Above the limit: **escalate**, citing `provisional-credit`, with
   no action. Otherwise **approve provisional credit**, citing `duplicate-same-authorisation`, with the action
   `issue_provisional_credit` for that amount.

## Before answering

- State in the rationale both postings' ids, amounts and dates, and whether they share an authorisation.
- The amount in dispute is the cardholder's claim. The credit comes from the ledger: one posting, at most.
- The outcome and its action must match: approve with `issue_provisional_credit`, decline with `close_dispute`,
  escalate with no action.
