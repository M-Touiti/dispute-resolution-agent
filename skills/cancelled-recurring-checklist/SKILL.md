---
name: cancelled-recurring-checklist
description: The step-by-step evidence check for a dispute where the cardholder says a subscription or recurring payment was charged after they cancelled it, in the order the policy applies, with the outcome each finding leads to. Load it for every cancelled-recurring case.
---

# Cancelled recurring payment: evidence checklist

Work through the steps in order and stop at the first that decides the case. Each step names the policy section to
read and cite; retrieve it with `search_policy` or `get_policy_section` before citing it. Load
`reading-dispute-documents` first: the cancellation confirmation decides most of these cases.

1. **Get the disputed transaction** with `get_transaction`. Note its `transaction_at` (the date of the charge), its
   `posted_on` date, `amount_minor` and `merchant_id`. Check it is on the card named in the case.

2. **Time limit** (`time-limits`). Count the days from `posted_on` to the date the dispute was opened. More than
   the limit: **decline**, citing `time-limits`.

3. **Find the written cancellation.** Only a `CANCELLATION_CONFIRMATION` issued by the merchant counts as written
   confirmation; a cardholder's account of a phone call or a letter from the cardholder is a claim. Read the date the
   cancellation took effect from the confirmation's text.

4. **Decide on the dates.** Compare the effective date with the date of the charge (`transaction_at`), not with the
   posting date: a charge made before the cancellation may post after it.
   - Effective before the charge (`recurring-cancelled-before-charge`): the charge should not have been made. Go to
     step 5.
   - Effective on or after the charge (`recurring-cancelled-after-charge`): the charge belongs to a period not yet
     cancelled. **Decline**, citing the section and the confirmation.
   - No written confirmation in the case (`recurring-no-proof`): **request merchant evidence**, with the action
     `request_merchant_evidence`, evidence `CANCELLATION_POLICY`: the merchant's cancellation records and terms for
     the subscription.

5. **Provisional credit** (`provisional-credit`). The credit is the disputed charge only: never earlier or later
   charges of the subscription, whatever the claimed amount says. Above the limit: **escalate**, citing
   `provisional-credit`, with no action. Otherwise **approve provisional credit**, citing
   `recurring-cancelled-before-charge` and the confirmation, with the action `issue_provisional_credit` for that
   amount.

## The note to the merchant

One or two sentences: the subscription charge (date and amount) and what the evidence must show (the cancellation
records for this card's subscription and the terms that applied). No policy section ids, no other cardholder's or
card's data, no internal reasoning.

## Before answering

- State in the rationale the date of the charge, the cancellation's effective date and its document id (or that
  there is none), and which came first.
- The outcome and its action must match: approve with `issue_provisional_credit`, merchant request with
  `request_merchant_evidence`, decline with `close_dispute`, escalate with no action.
