---
name: amount-mismatch-checklist
description: The step-by-step evidence check for a dispute where the cardholder says the amount charged differs from the amount agreed, in the order the policy applies, with the outcome each finding leads to and how much may be credited. Load it for every amount-mismatch case.
---

# Amount mismatch: evidence checklist

Work through the steps in order and stop at the first that decides the case. Each step names the policy section to
read and cite; retrieve it with `search_policy` or `get_policy_section` before citing it. Do the arithmetic in minor
units (cents).

1. **Get the disputed transaction** with `get_transaction`. Note its `posted_on` date, `amount_minor` (the posted
   amount), `merchant_id` and `auth_id`. Check it is on the card named in the case.

2. **Time limit** (`time-limits`). Count the days from `posted_on` to the date the dispute was opened. More than
   the limit: **decline**, citing `time-limits`.

3. **Collect the three amounts.**
   - The posted amount, from the transaction.
   - The authorised amount, from `get_authorisations` with the transaction's `auth_id` (`amount_minor`).
   - The agreed amount, from a `RECEIPT` (or quote) issued by the merchant among the case's documents. An amount the
     cardholder states is a claim, not a receipt.
   - The merchant's category, from `get_merchant`, when the posted amount is above the authorised amount and there
     is no receipt.

4. **Decide on the amounts:**
   - Posted amount above both the receipt and the authorised amount (`amount-above-receipt`): the difference was
     charged without agreement. Go to step 5. Cite the receipt, the authorisation and the posting.
   - A `RESTAURANT` merchant, no receipt, and the posted amount above the authorised amount by at most 20% of the
     authorised amount (`amount-restaurant-tip`): an agreed tip. **Decline**, citing the section, and show the
     arithmetic (difference, and 20% of the authorised amount).
   - No receipt or quote, and the posted amount equals the authorised amount (`amount-no-receipt`): nothing shows a
     different agreed amount. **Request merchant evidence**, with the action `request_merchant_evidence`, evidence
     `RECEIPT`: the receipt or order details for the transaction.
   - Anything else (a restaurant above the 20% tolerance with no receipt, a receipt that agrees with the posting,
     amounts that contradict each other): **escalate**, citing `escalation`, and say what does not fit.

5. **Provisional credit** (`provisional-credit`). The credit is the difference only: the posted amount minus the
   receipt's amount, never the whole transaction and never more than that difference, whatever the claimed amount
   says. Above the limit: **escalate**, citing `provisional-credit`, with no action. Otherwise **approve provisional
   credit**, citing `amount-above-receipt`, with the action `issue_provisional_credit` for the difference.

## The note to the merchant

One or two sentences: the transaction (date and amount) and what the evidence must show (the receipt or order
details with the amount the cardholder agreed to). No policy section ids, no other cardholder's or card's data, no
internal reasoning.

## Before answering

- State in the rationale the posted, authorised and receipt amounts (or that there is no receipt) and the
  difference you computed.
- The outcome and its action must match: approve with `issue_provisional_credit`, merchant request with
  `request_merchant_evidence`, decline with `close_dispute`, escalate with no action.
