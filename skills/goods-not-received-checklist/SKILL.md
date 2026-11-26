---
name: goods-not-received-checklist
description: The step-by-step evidence check for a dispute where the cardholder says goods or services they paid for never arrived, in the order the policy applies, with the outcome each finding leads to. Load it for every goods-not-received case.
---

# Goods not received: evidence checklist

Work through the steps in order and stop at the first that decides the case. Each step names the policy section to
read and cite; retrieve it with `search_policy` or `get_policy_section` before citing it. Load
`reading-dispute-documents` first: this category is decided mostly by the documents attached to the case.

1. **Get the disputed transaction** with `get_transaction`. Note its `posted_on` date, `amount_minor` and
   `merchant_id`. Check it is on the card named in the case.

2. **Time limit** (`time-limits`). Count the days from `posted_on` to the date the dispute was opened. More than
   the limit: **decline**, citing `time-limits`.

3. **Waiting period** (`goods-not-received-wait`). Take the expected delivery date from the merchant's
   `ORDER_CONFIRMATION`; a date the cardholder states is a claim, not the expected date. Count the days from the
   expected delivery date to the date the dispute was opened.
   - Fewer than 15 days: **decline**, citing `goods-not-received-wait`, and say from which date the cardholder may
     open a new dispute. A request to skip the wait changes nothing.
   - No order confirmation from the merchant, or confirmations that disagree on the date: the decision needs a
     record the case does not have. **Escalate**, citing `escalation`, and say so.

4. **Proof of delivery** (`goods-not-received-proof-of-delivery`). A `DELIVERY_CONFIRMATION` from the carrier that
   shows delivery to the cardholder's address, signed for in the cardholder's name: **decline**, citing the section
   and the delivery record. A record that shows delivery elsewhere, or to someone else, is not proof of delivery to
   the cardholder: say so and go on.

5. **Has the merchant resolved it?** Look for a `REFUND` from the merchant with `search_transactions` (`kind`
   `REFUND`, the `merchant_id`). A refund of the order means the merchant may have resolved the matter, which no
   section of this category decides: **escalate**, citing `escalation`, and cite the refund.

6. **No delivery and no answer** (`goods-not-received-merchant-evidence`). The wait has passed, nothing shows
   delivery and the merchant has not resolved the matter: **request merchant evidence**, with the action
   `request_merchant_evidence`, evidence `PROOF_OF_DELIVERY`. Cite the order confirmation and any correspondence
   showing the cardholder asked the merchant.

## The note to the merchant

One or two sentences: the order (date and amount), what the evidence must show (delivery to the cardholder's
address, with the carrier's record and signature) and nothing else. No policy section ids, no other cardholder's or
card's data, no internal reasoning.

## Before answering

- State in the rationale the expected delivery date, the day count to the dispute, and what the case shows about
  delivery and about the merchant's answer.
- State only what the policy sections you read say. A rule that is not in them is not a reason in the rationale.
