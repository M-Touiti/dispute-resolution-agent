---
name: reading-an-authorisation-trail
description: How to read the authorisation behind a transaction (entry mode, 3-D Secure, CVV, PIN, fraud flag, declines) and what each value says about who made the payment. Load it before judging whether a cardholder made a payment.
---

# Reading an authorisation trail

Every purchase starts with an authorisation: the merchant asked the bank to approve a payment and the bank answered.
The transaction's `auth_id` links to it; `get_authorisations` with that `auth_id` returns it. A refund has no
authorisation.

## The fields

| Field | Values | What it tells you |
|---|---|---|
| `entry_mode` | `CHIP`, `CONTACTLESS` | The card was physically at the merchant's terminal. |
| | `ECOMMERCE` | Card details typed online: the card itself was not present. |
| | `CREDENTIAL_ON_FILE` | The merchant charged a stored card, typically a subscription. |
| `three_ds_result` | `AUTHENTICATED` | The cardholder confirmed the online payment with the bank (3-D Secure). |
| | `NOT_PERFORMED` | An online payment without that confirmation. |
| | `NOT_APPLICABLE` | Not an online payment; 3-D Secure does not apply. |
| `cvv_result` | `MATCH` | The security code on the back of the card was given and correct. |
| | `NOT_PRESENT` | No security code was given. |
| `pin_verified` | `true` / `false` | The PIN was entered and correct at a terminal. |
| `fraud_flag` | `true` / `false` | The bank's fraud screening marked the attempt as suspicious. |
| `response` | `APPROVED`, `DECLINED` | Whether the bank approved; a decline carries `decline_reason`. |

## Reading them together

- **Strong signs the cardholder made the payment:** `CHIP` with `pin_verified` true; `ECOMMERCE` with
  `three_ds_result` `AUTHENTICATED`. Earlier approved purchases at the same merchant on the same card add weight.
- **Signs of a payment the cardholder may not have made:** `ECOMMERCE` with `three_ds_result` `NOT_PERFORMED`, and
  the `fraud_flag` set. A merchant in another city or country than the cardholder's adds weight but proves nothing
  alone.
- **The fraud flag is a screening signal, not a verdict.** It does not override strong authentication, and it does
  not override the time limit.
- **Declined attempts** before or after the disputed payment are context (for example someone trying a card), never
  evidence of a charge: a declined authorisation moved no money.

## Citing

Cite the authorisation by its `auth_id` and the transaction by its `transaction_id`. Quote the field values you
rely on in the rationale, for example "entry mode ECOMMERCE, 3-D Secure NOT_PERFORMED, fraud flag set".
