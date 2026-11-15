"""Row types. Field order is the column order in CSV and SQL; the first field is the primary key."""

import datetime as dt
from dataclasses import dataclass
from enum import StrEnum


class Category(StrEnum):
    UNAUTHORISED_TRANSACTION = "UNAUTHORISED_TRANSACTION"
    DUPLICATE_CHARGE = "DUPLICATE_CHARGE"
    GOODS_NOT_RECEIVED = "GOODS_NOT_RECEIVED"
    CANCELLED_RECURRING = "CANCELLED_RECURRING"
    AMOUNT_MISMATCH = "AMOUNT_MISMATCH"


class Decision(StrEnum):
    APPROVE_PROVISIONAL_CREDIT = "APPROVE_PROVISIONAL_CREDIT"
    REQUEST_MERCHANT_EVIDENCE = "REQUEST_MERCHANT_EVIDENCE"
    DECLINE = "DECLINE"
    ESCALATE = "ESCALATE"


class Difficulty(StrEnum):
    EASY = "EASY"
    MEDIUM = "MEDIUM"
    HARD = "HARD"


@dataclass(frozen=True, slots=True)
class Cardholder:
    cardholder_id: str
    given_name: str
    family_name: str
    email: str
    city: str
    country: str
    customer_since: dt.date


@dataclass(frozen=True, slots=True)
class Card:
    card_id: str
    cardholder_id: str
    pan_last4: str
    product: str
    status: str
    issued_on: dt.date
    expiry: str


@dataclass(frozen=True, slots=True)
class Merchant:
    merchant_id: str
    name: str
    category: str
    city: str
    country: str
    statement_descriptor: str
    ecommerce: bool
    recurring_billing: bool


@dataclass(frozen=True, slots=True)
class Authorisation:
    auth_id: str
    card_id: str
    merchant_id: str
    requested_at: dt.datetime
    amount_minor: int
    currency: str
    entry_mode: str
    three_ds_result: str
    cvv_result: str
    pin_verified: bool
    response: str
    decline_reason: str | None
    fraud_flag: bool


@dataclass(frozen=True, slots=True)
class Transaction:
    transaction_id: str
    auth_id: str | None
    card_id: str
    merchant_id: str
    kind: str
    transaction_at: dt.datetime
    posted_on: dt.date
    amount_minor: int
    currency: str
    description: str
    related_transaction_id: str | None


@dataclass(frozen=True, slots=True)
class Dispute:
    dispute_id: str
    cardholder_id: str
    card_id: str
    transaction_id: str
    category: Category
    opened_at: dt.datetime
    claimed_amount_minor: int
    currency: str
    cardholder_statement: str


@dataclass(frozen=True, slots=True)
class DisputeDocument:
    document_id: str
    dispute_id: str
    kind: str
    issued_by: str
    dated: dt.date
    summary: str


@dataclass(frozen=True, slots=True)
class GroundTruth:
    """The expected outcome of a dispute. Written to CSV for the evaluation only, never to the SQL seed."""

    dispute_id: str
    category: Category
    scenario: str
    difficulty: Difficulty
    expected_decision: Decision
    required_evidence_ids: tuple[str, ...]
    allowed_actions: tuple[str, ...]
    forbidden_actions: tuple[str, ...]
    rationale: str
