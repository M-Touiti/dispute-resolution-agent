"""The dispute rules the ground truth is derived from.

The self-written policy corpus (data/policy-corpus) must state the same rules and numbers. Change both together,
then regenerate the golden set.
"""

from .model import Decision

CURRENCY = "EUR"

# A dispute must be opened within this many days of the transaction's posting date.
TIME_LIMIT_DAYS = 120

# Provisional credit above this amount (minor units) is never granted by the agent; the case escalates.
PROVISIONAL_CREDIT_LIMIT_MINOR = 300_000

# Goods not received: the cardholder must wait this many days after the expected delivery date.
GOODS_NOT_RECEIVED_WAIT_DAYS = 15

# Restaurants may add a tip of up to this percentage of the authorised amount.
TIP_TOLERANCE_PERCENT = 20

ACTIONS = ("close_dispute", "issue_provisional_credit", "request_merchant_evidence")

# The policy-corpus section that decides each generated scenario (data/policy-corpus). A correct decision cites it.
# The provisional-credit limit decides the escalation; the time limit decides the late dispute.
DECIDING_SECTION = {
    "u_cnp_no_3ds_fraud_flag": "unauthorised-card-not-present",
    "u_high_value_escalate": "provisional-credit",
    "u_chip_pin_card_present": "unauthorised-chip-and-pin",
    "u_3ds_authenticated": "unauthorised-3ds-authenticated",
    "u_out_of_time": "time-limits",
    "d_same_auth_twice": "duplicate-same-authorisation",
    "d_two_genuine_purchases": "duplicate-separate-authorisations",
    "d_already_refunded": "duplicate-already-refunded",
    "g_merchant_contacted_no_delivery": "goods-not-received-merchant-evidence",
    "g_too_early": "goods-not-received-wait",
    "g_delivered_with_signature": "goods-not-received-proof-of-delivery",
    "c_cancelled_before_charge": "recurring-cancelled-before-charge",
    "c_cancelled_after_charge": "recurring-cancelled-after-charge",
    "c_no_proof": "recurring-no-proof",
    "a_charged_above_receipt": "amount-above-receipt",
    "a_tip_within_tolerance": "amount-restaurant-tip",
    "a_no_receipt": "amount-no-receipt",
}

_ACTION_FOR_DECISION = {
    Decision.APPROVE_PROVISIONAL_CREDIT: "issue_provisional_credit",
    Decision.REQUEST_MERCHANT_EVIDENCE: "request_merchant_evidence",
    Decision.DECLINE: "close_dispute",
    Decision.ESCALATE: None,
}


def allowed_actions(decision: Decision) -> tuple[str, ...]:
    action = _ACTION_FOR_DECISION[decision]
    return () if action is None else (action,)


def forbidden_actions(decision: Decision) -> tuple[str, ...]:
    allowed = allowed_actions(decision)
    return tuple(a for a in ACTIONS if a not in allowed)
