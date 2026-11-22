"""Generates the dataset from one seeded random source.

Determinism rules: every random draw goes through ``self.rng``; nothing reads the clock, the environment, or the
iteration order of a set or a hash; ids are sequential per prefix; amounts are integer minor units.
"""

import datetime as dt
import random
from collections.abc import Callable
from dataclasses import dataclass, field

from . import catalog, policy
from .model import (
    Authorisation,
    Card,
    Cardholder,
    Category,
    Decision,
    Difficulty,
    Dispute,
    DisputeDocument,
    GroundTruth,
    Merchant,
    Transaction,
)


@dataclass(frozen=True, slots=True)
class Config:
    seed: int
    as_of: dt.date = dt.date(2026, 11, 1)
    cardholders: int = 200
    merchants: int = 80
    disputes: int = 170
    history_days: int = 180

    def __post_init__(self) -> None:
        if self.merchants < len(catalog.CATEGORIES):
            raise ValueError(f"merchants must be at least {len(catalog.CATEGORIES)} (one per category)")
        if self.cardholders < 1 or self.disputes < 0:
            raise ValueError("cardholders must be positive and disputes non-negative")
        if self.history_days < 180:
            raise ValueError("history_days must be at least 180 to cover the out-of-time scenarios")


@dataclass
class Dataset:
    cardholders: list[Cardholder] = field(default_factory=list)
    cards: list[Card] = field(default_factory=list)
    merchants: list[Merchant] = field(default_factory=list)
    authorisations: list[Authorisation] = field(default_factory=list)
    transactions: list[Transaction] = field(default_factory=list)
    disputes: list[Dispute] = field(default_factory=list)
    dispute_documents: list[DisputeDocument] = field(default_factory=list)
    ground_truth: list[GroundTruth] = field(default_factory=list)


def generate(config: Config) -> Dataset:
    return _Generator(config).run()


def eur(minor: int) -> str:
    return f"{minor // 100}.{minor % 100:02d} EUR"


@dataclass(frozen=True, slots=True)
class _Scenario:
    name: str
    category: Category
    difficulty: Difficulty
    build: Callable[["_Generator", "_Case"], None]


@dataclass(slots=True)
class _Case:
    dispute_id: str
    holder: Cardholder
    card: Card
    opened_at: dt.datetime


class _Generator:
    def __init__(self, config: Config) -> None:
        self.c = config
        self.rng = random.Random(config.seed)
        self.ds = Dataset()
        self._counters: dict[str, int] = {}
        self.as_of = dt.datetime.combine(config.as_of, dt.time(), tzinfo=dt.UTC)
        self._holders: dict[str, Cardholder] = {}
        self._subscriptions: dict[str, list[str]] = {}

    # ---- ids and time -------------------------------------------------------------------------------------------

    def _id(self, prefix: str) -> str:
        n = self._counters.get(prefix, 0) + 1
        self._counters[prefix] = n
        return f"{prefix}-{n:06d}"

    def _days_before(self, anchor: dt.datetime, lo: int, hi: int) -> dt.datetime:
        """A time-of-day between 08:00 and 21:59 on a day ``lo..hi`` days before ``anchor``."""
        day = (anchor - dt.timedelta(days=self.rng.randint(lo, hi))).date()
        seconds = self.rng.randint(8 * 3600, 22 * 3600 - 1)
        return dt.datetime.combine(day, dt.time(), tzinfo=dt.UTC) + dt.timedelta(seconds=seconds)

    # ---- reference data -----------------------------------------------------------------------------------------

    def run(self) -> Dataset:
        self._merchants()
        self._cardholders_and_cards()
        self._history()
        self._disputes()
        return self.ds

    def _merchants(self) -> None:
        used: set[str] = set()  # membership only; never iterated
        for i in range(self.c.merchants):
            cat = catalog.CATEGORIES[i % len(catalog.CATEGORIES)]
            city, country = self.rng.choice(catalog.CITIES)
            for _ in range(1000):
                name = f"{self.rng.choice(catalog.MERCHANT_PREFIXES)} {self.rng.choice(cat.nouns)}"
                if name not in used:
                    break
            else:
                raise ValueError("ran out of merchant names; lower --merchants")
            used.add(name)
            self.ds.merchants.append(
                Merchant(
                    merchant_id=self._id("MER"),
                    name=name,
                    category=cat.code,
                    city=city,
                    country=country,
                    statement_descriptor=name.upper()[:22],
                    ecommerce=cat.ecommerce,
                    recurring_billing=cat.recurring,
                )
            )

    def _cardholders_and_cards(self) -> None:
        for i in range(self.c.cardholders):
            given = self.rng.choice(catalog.GIVEN_NAMES)
            family = self.rng.choice(catalog.FAMILY_NAMES)
            city, country = self.rng.choice(catalog.CITIES)
            since = self.c.as_of - dt.timedelta(days=self.rng.randint(400, 3650))
            holder = Cardholder(
                cardholder_id=self._id("CH"),
                given_name=given,
                family_name=family,
                email=f"{given.lower()}.{family.lower()}{i + 1}@example.test",
                city=city,
                country=country,
                customer_since=since,
            )
            self.ds.cardholders.append(holder)
            self._holders[holder.cardholder_id] = holder
            for k in range(1 if self.rng.random() < 0.75 else 2):
                latest_issue = (self.c.as_of - since).days - self.c.history_days - 10
                issued = since + dt.timedelta(days=self.rng.randint(0, latest_issue))
                # Renewed every four years under the same id: the current expiry is after the dataset's "today",
                # so no transaction falls after its card's expiry (generator v3).
                years = 4
                while dt.date(issued.year + years, issued.month, 1) <= self.c.as_of:
                    years += 4
                self.ds.cards.append(
                    Card(
                        card_id=self._id("CRD"),
                        cardholder_id=holder.cardholder_id,
                        pan_last4=f"{self.rng.randint(0, 9999):04d}",
                        product=self.rng.choice(catalog.CARD_PRODUCTS),
                        status="ACTIVE" if k == 0 or self.rng.random() < 0.9 else "BLOCKED",
                        issued_on=issued,
                        expiry=f"{issued.year + years}-{issued.month:02d}",
                    )
                )

    # ---- purchases ----------------------------------------------------------------------------------------------

    def _merchant(self, predicate: Callable[[Merchant], bool], prefer_city: str | None = None) -> Merchant:
        candidates = [m for m in self.ds.merchants if predicate(m)]
        if not candidates:
            raise ValueError("no merchant matches the scenario; raise --merchants")
        if prefer_city is not None:
            local = [m for m in candidates if m.city == prefer_city]
            candidates = local or candidates
        return self.rng.choice(candidates)

    def _amount(self, m: Merchant) -> int:
        cat = catalog.CATEGORY_BY_CODE[m.category]
        return self.rng.randint(cat.min_minor, cat.max_minor)

    def _authorise(
        self,
        card: Card,
        m: Merchant,
        when: dt.datetime,
        amount: int,
        *,
        entry_mode: str | None = None,
        three_ds: str | None = None,
        cvv: str | None = None,
        pin: bool | None = None,
        fraud: bool = False,
        response: str = "APPROVED",
        decline_reason: str | None = None,
    ) -> Authorisation:
        if entry_mode is None:
            if m.recurring_billing:
                entry_mode = "CREDENTIAL_ON_FILE"
            elif m.ecommerce:
                entry_mode = "ECOMMERCE"
            else:
                entry_mode = "CHIP" if self.rng.random() < 0.4 else "CONTACTLESS"
        if three_ds is None:
            if entry_mode == "ECOMMERCE":
                three_ds = "AUTHENTICATED" if self.rng.random() < 0.9 else "NOT_PERFORMED"
            else:
                three_ds = "NOT_APPLICABLE"
        if cvv is None:
            cvv = "MATCH" if entry_mode == "ECOMMERCE" else "NOT_PRESENT"
        if pin is None:
            pin = entry_mode == "CHIP"
        auth = Authorisation(
            auth_id=self._id("AUTH"),
            card_id=card.card_id,
            merchant_id=m.merchant_id,
            requested_at=when,
            amount_minor=amount,
            currency=policy.CURRENCY,
            entry_mode=entry_mode,
            three_ds_result=three_ds,
            cvv_result=cvv,
            pin_verified=pin,
            response=response,
            decline_reason=decline_reason,
            fraud_flag=fraud,
        )
        self.ds.authorisations.append(auth)
        return auth

    def _post(
        self,
        card: Card,
        m: Merchant,
        when: dt.datetime,
        amount: int,
        *,
        auth_id: str | None,
        kind: str = "PURCHASE",
        related: str | None = None,
    ) -> Transaction:
        txn = Transaction(
            transaction_id=self._id("TXN"),
            auth_id=auth_id,
            card_id=card.card_id,
            merchant_id=m.merchant_id,
            kind=kind,
            transaction_at=when,
            posted_on=when.date() + dt.timedelta(days=1),
            amount_minor=amount,
            currency=policy.CURRENCY,
            description=m.statement_descriptor,
            related_transaction_id=related,
        )
        self.ds.transactions.append(txn)
        return txn

    def _purchase(
        self, card: Card, m: Merchant, when: dt.datetime, amount: int, *, posted_amount: int | None = None, **auth_kw
    ) -> tuple[Authorisation, Transaction]:
        auth = self._authorise(card, m, when, amount, **auth_kw)
        txn = self._post(card, m, when, amount if posted_amount is None else posted_amount, auth_id=auth.auth_id)
        return auth, txn

    # ---- background history -------------------------------------------------------------------------------------

    def _history(self) -> None:
        recurring = [m for m in self.ds.merchants if m.recurring_billing]
        for card in self.ds.cards:
            holder = self._holders[card.cardholder_id]
            shops = [m for m in self.ds.merchants if not m.recurring_billing and (m.ecommerce or m.city == holder.city)]
            for _ in range(self.rng.randint(15, 40)):
                m = self.rng.choice(shops)
                when = self._days_before(self.as_of, 1, self.c.history_days)
                amount = self._amount(m)
                if self.rng.random() < 0.03:
                    reason = self.rng.choice(("INSUFFICIENT_FUNDS", "DO_NOT_HONOUR"))
                    self._authorise(card, m, when, amount, response="DECLINED", decline_reason=reason)
                    continue
                posted = amount
                if m.category == catalog.RESTAURANT and self.rng.random() < 0.6:
                    posted = amount + amount * self.rng.randint(5, 15) // 100
                self._purchase(card, m, when, amount, posted_amount=posted)
            subs = self.rng.sample(recurring, k=self.rng.randint(0, min(2, len(recurring))))
            self._subscriptions[card.card_id] = [m.merchant_id for m in subs]
            for m in subs:
                amount = self._amount(m)
                first = self._days_before(self.as_of, self.c.history_days - 25, self.c.history_days - 1)
                for month in range(self.c.history_days // 30):
                    when = first + dt.timedelta(days=30 * month)
                    if when < self.as_of:
                        self._purchase(card, m, when, amount)

    # ---- disputes -----------------------------------------------------------------------------------------------

    def _disputes(self) -> None:
        if self.c.disputes > len(self.ds.cards):
            raise ValueError(f"disputes ({self.c.disputes}) exceeds cards ({len(self.ds.cards)}); add cardholders")
        cards = self.rng.sample(self.ds.cards, k=self.c.disputes)
        for i, card in enumerate(cards):
            scenario = SCENARIOS[i % len(SCENARIOS)]
            case = _Case(
                dispute_id=self._id("DSP"),
                holder=self._holders[card.cardholder_id],
                card=card,
                opened_at=self._days_before(self.as_of, 1, 10),
            )
            scenario.build(self, case)

    def _open(
        self,
        case: _Case,
        scenario: str,
        txn: Transaction,
        claimed: int,
        statement: str,
        decision: Decision,
        evidence: tuple[str, ...],
        rationale: str,
    ) -> None:
        s = SCENARIO_BY_NAME[scenario]
        self.ds.disputes.append(
            Dispute(
                dispute_id=case.dispute_id,
                cardholder_id=case.holder.cardholder_id,
                card_id=case.card.card_id,
                transaction_id=txn.transaction_id,
                category=s.category,
                opened_at=case.opened_at,
                claimed_amount_minor=claimed,
                currency=policy.CURRENCY,
                cardholder_statement=statement,
            )
        )
        self.ds.ground_truth.append(
            GroundTruth(
                dispute_id=case.dispute_id,
                category=s.category,
                scenario=scenario,
                difficulty=s.difficulty,
                expected_decision=decision,
                required_evidence_ids=tuple(sorted(evidence)),
                allowed_actions=policy.allowed_actions(decision),
                forbidden_actions=policy.forbidden_actions(decision),
                deciding_section=policy.DECIDING_SECTION[scenario],
                rationale=rationale,
            )
        )

    def _doc(self, case: _Case, kind: str, issued_by: str, dated: dt.date, summary: str) -> DisputeDocument:
        doc = DisputeDocument(
            document_id=self._id("DOC"),
            dispute_id=case.dispute_id,
            kind=kind,
            issued_by=issued_by,
            dated=dated,
            summary=summary,
        )
        self.ds.dispute_documents.append(doc)
        return doc

    def _in_time(self, case: _Case, lo: int = 10, hi: int = 100) -> dt.datetime:
        return self._days_before(case.opened_at, lo, hi)

    # Unauthorised transaction -----------------------------------------------------------------------------------

    def _merchants_used(self, card_id: str) -> set[str]:
        return {t.merchant_id for t in self.ds.transactions if t.card_id == card_id}

    def _cnp_fraud(self, case: _Case, when: dt.datetime, amount: int, categories: tuple[str, ...]):
        # A merchant this card has never used, so the cardholder's history does not contradict the claim (v3).
        used = self._merchants_used(case.card.card_id)
        m = self._merchant(
            lambda m: m.category in categories and m.city != case.holder.city and m.merchant_id not in used
        )
        return m, *self._purchase(
            case.card,
            m,
            when,
            amount,
            entry_mode="ECOMMERCE",
            three_ds="NOT_PERFORMED",
            cvv="NOT_PRESENT",
            pin=False,
            fraud=True,
        )

    def _unrecognised(self, m: Merchant, txn: Transaction) -> str:
        statements = [
            f"I do not recognise the payment of {eur(txn.amount_minor)} to {m.statement_descriptor} on "
            f"{txn.transaction_at.date()}. My card has been with me the whole time."
        ]
        shopped_there_before = any(
            t.card_id == txn.card_id and t.merchant_id == m.merchant_id and t.transaction_at < txn.transaction_at
            for t in self.ds.transactions
        )
        if not shopped_there_before:  # only then may the cardholder say so (v3)
            statements.append(
                f"There is a charge from {m.statement_descriptor} for {eur(txn.amount_minor)} that I did not make. "
                "I have never shopped there and I did not give my card details to anyone."
            )
        return self.rng.choice(statements)

    def u_cnp_no_3ds_fraud_flag(self, case: _Case) -> None:
        when = self._in_time(case)
        amount = self.rng.randint(2_500, 250_000)
        m, auth, txn = self._cnp_fraud(case, when, amount, (catalog.ELECTRONICS_ONLINE, catalog.FASHION_ONLINE))
        self._open(
            case,
            "u_cnp_no_3ds_fraud_flag",
            txn,
            txn.amount_minor,
            self._unrecognised(m, txn),
            Decision.APPROVE_PROVISIONAL_CREDIT,
            (txn.transaction_id, auth.auth_id),
            "Card not present, no 3-D Secure, fraud flag set, within the time limit and under the "
            "provisional credit limit.",
        )

    def u_high_value_escalate(self, case: _Case) -> None:
        when = self._in_time(case)
        limit = policy.PROVISIONAL_CREDIT_LIMIT_MINOR
        amount = self.rng.randint(limit + 1, limit + 150_000)
        m, auth, txn = self._cnp_fraud(case, when, amount, (catalog.TRAVEL, catalog.ELECTRONICS_ONLINE))
        self._open(
            case,
            "u_high_value_escalate",
            txn,
            txn.amount_minor,
            self._unrecognised(m, txn),
            Decision.ESCALATE,
            (txn.transaction_id, auth.auth_id),
            "Meets the unauthorised-transaction criteria but exceeds the provisional credit limit; a human decides.",
        )

    def u_chip_pin_card_present(self, case: _Case) -> None:
        m = self._merchant(lambda m: not m.ecommerce and not m.recurring_billing, prefer_city=case.holder.city)
        when = self._in_time(case)
        for _ in range(2):  # earlier genuine visits to the same merchant
            self._purchase(case.card, m, self._days_before(when, 20, 80), self._amount(m), entry_mode="CHIP")
        auth, txn = self._purchase(case.card, m, when, self._amount(m), entry_mode="CHIP", pin=True)
        self._open(
            case,
            "u_chip_pin_card_present",
            txn,
            txn.amount_minor,
            self._unrecognised(m, txn),
            Decision.DECLINE,
            (txn.transaction_id, auth.auth_id),
            "Card present with chip and PIN verified, no fraud flag, and the cardholder has paid this merchant before.",
        )

    def u_3ds_authenticated(self, case: _Case) -> None:
        m = self._merchant(lambda m: m.ecommerce and not m.recurring_billing)
        auth, txn = self._purchase(
            case.card,
            m,
            self._in_time(case),
            self._amount(m),
            entry_mode="ECOMMERCE",
            three_ds="AUTHENTICATED",
            cvv="MATCH",
            pin=False,
        )
        self._open(
            case,
            "u_3ds_authenticated",
            txn,
            txn.amount_minor,
            self._unrecognised(m, txn),
            Decision.DECLINE,
            (txn.transaction_id, auth.auth_id),
            "The cardholder authenticated the payment with 3-D Secure and the CVV matched; no fraud flag.",
        )

    def u_out_of_time(self, case: _Case) -> None:
        when = self._days_before(case.opened_at, policy.TIME_LIMIT_DAYS + 10, policy.TIME_LIMIT_DAYS + 50)
        amount = self.rng.randint(2_500, 150_000)
        m, _auth, txn = self._cnp_fraud(case, when, amount, (catalog.ELECTRONICS_ONLINE, catalog.FASHION_ONLINE))
        self._open(
            case,
            "u_out_of_time",
            txn,
            txn.amount_minor,
            self._unrecognised(m, txn),
            Decision.DECLINE,
            (txn.transaction_id,),
            "Opened after the time limit; the fraud indicators do not change that.",
        )

    # Duplicate charge -------------------------------------------------------------------------------------------

    def _shop(self, case: _Case) -> Merchant:
        return self._merchant(lambda m: not m.recurring_billing and (m.ecommerce or m.city == case.holder.city))

    def _charged_twice(self, case: _Case) -> tuple[Merchant, Authorisation, Transaction, Transaction]:
        m = self._shop(case)
        auth, first = self._purchase(case.card, m, self._in_time(case), self._amount(m))
        second = self._post(
            case.card,
            m,
            first.transaction_at + dt.timedelta(minutes=self.rng.randint(1, 5)),
            first.amount_minor,
            auth_id=auth.auth_id,
        )
        return m, auth, first, second

    def _twice_statement(self, m: Merchant, txn: Transaction) -> str:
        return (
            f"I was charged twice by {m.statement_descriptor} for {eur(txn.amount_minor)} on "
            f"{txn.transaction_at.date()}. I only made one purchase."
        )

    def d_same_auth_twice(self, case: _Case) -> None:
        m, auth, first, second = self._charged_twice(case)
        self._open(
            case,
            "d_same_auth_twice",
            second,
            second.amount_minor,
            self._twice_statement(m, second),
            Decision.APPROVE_PROVISIONAL_CREDIT,
            (first.transaction_id, second.transaction_id, auth.auth_id),
            "Two postings of the same amount against a single authorisation.",
        )

    def d_two_genuine_purchases(self, case: _Case) -> None:
        m = self._merchant(lambda m: not m.ecommerce and not m.recurring_billing, prefer_city=case.holder.city)
        amount = self._amount(m)
        when = self._in_time(case)
        auth1, first = self._purchase(case.card, m, when, amount)
        later = when + dt.timedelta(hours=self.rng.randint(2, 9))
        auth2, second = self._purchase(case.card, m, later, amount)
        self._open(
            case,
            "d_two_genuine_purchases",
            second,
            second.amount_minor,
            self._twice_statement(m, second),
            Decision.DECLINE,
            (first.transaction_id, second.transaction_id, auth1.auth_id, auth2.auth_id),
            "Two separate authorisations hours apart: two purchases, not a duplicate.",
        )

    def d_already_refunded(self, case: _Case) -> None:
        m, _auth, _first, second = self._charged_twice(case)
        refund_at = second.transaction_at + dt.timedelta(days=self.rng.randint(2, 6))
        refund = self._post(
            case.card, m, refund_at, second.amount_minor, auth_id=None, kind="REFUND", related=second.transaction_id
        )
        self._open(
            case,
            "d_already_refunded",
            second,
            second.amount_minor,
            self._twice_statement(m, second),
            Decision.DECLINE,
            (second.transaction_id, refund.transaction_id),
            "The duplicate posting was already refunded by the merchant.",
        )

    # Goods not received -----------------------------------------------------------------------------------------

    def _order(self, case: _Case, lo: int, hi: int) -> tuple[Merchant, Transaction]:
        m = self._merchant(lambda m: m.category in (catalog.ELECTRONICS_ONLINE, catalog.FASHION_ONLINE))
        _auth, txn = self._purchase(
            case.card,
            m,
            self._in_time(case, lo, hi),
            self._amount(m),
            entry_mode="ECOMMERCE",
            three_ds="AUTHENTICATED",
            cvv="MATCH",
            pin=False,
        )
        return m, txn

    def _order_doc(self, case: _Case, m: Merchant, txn: Transaction, expected: dt.date) -> DisputeDocument:
        return self._doc(
            case,
            "ORDER_CONFIRMATION",
            "MERCHANT",
            txn.transaction_at.date(),
            f"Order confirmation from {m.name}: total {eur(txn.amount_minor)}, expected delivery {expected}.",
        )

    def _not_received(self, m: Merchant, expected: dt.date) -> str:
        return (
            f"I ordered from {m.name} and the order was due on {expected}. It never arrived and I would like "
            "my money back."
        )

    def g_merchant_contacted_no_delivery(self, case: _Case) -> None:
        m, txn = self._order(case, 40, 100)
        expected = txn.posted_on + dt.timedelta(days=self.rng.randint(5, 10))
        order = self._order_doc(case, m, txn, expected)
        contact = self._doc(
            case,
            "MERCHANT_CORRESPONDENCE",
            "CARDHOLDER",
            expected + dt.timedelta(days=self.rng.randint(3, 10)),
            f"Email from the cardholder to {m.name} asking where the order is. No reply received.",
        )
        self._open(
            case,
            "g_merchant_contacted_no_delivery",
            txn,
            txn.amount_minor,
            self._not_received(m, expected),
            Decision.REQUEST_MERCHANT_EVIDENCE,
            (txn.transaction_id, order.document_id, contact.document_id),
            "Expected delivery date long passed and the merchant did not answer; ask the merchant for proof "
            "of delivery.",
        )

    def g_too_early(self, case: _Case) -> None:
        m, txn = self._order(case, 12, 20)
        expected = case.opened_at.date() - dt.timedelta(days=self.rng.randint(1, 10))
        order = self._order_doc(case, m, txn, expected)
        self._open(
            case,
            "g_too_early",
            txn,
            txn.amount_minor,
            self._not_received(m, expected),
            Decision.DECLINE,
            (txn.transaction_id, order.document_id),
            f"Opened less than {policy.GOODS_NOT_RECEIVED_WAIT_DAYS} days after the expected delivery date.",
        )

    def g_delivered_with_signature(self, case: _Case) -> None:
        m, txn = self._order(case, 25, 90)
        expected = txn.posted_on + dt.timedelta(days=self.rng.randint(3, 8))
        self._order_doc(case, m, txn, expected)
        h = case.holder
        delivery = self._doc(
            case,
            "DELIVERY_CONFIRMATION",
            "CARRIER",
            expected,
            f"Carrier tracking: parcel delivered on {expected} to the cardholder's address in "
            f"{h.city}, signed for by {h.given_name[0]}. {h.family_name}.",
        )
        self._open(
            case,
            "g_delivered_with_signature",
            txn,
            txn.amount_minor,
            self._not_received(m, expected),
            Decision.DECLINE,
            (txn.transaction_id, delivery.document_id),
            "Carrier proof of delivery to the cardholder's address, signed in the cardholder's name.",
        )

    # Cancelled recurring ----------------------------------------------------------------------------------------

    def _subscription(self, case: _Case) -> tuple[Merchant, Transaction]:
        current = self._subscriptions.get(case.card.card_id, [])
        m = self._merchant(lambda m: m.recurring_billing and m.merchant_id not in current)
        amount = self._amount(m)
        when = self._in_time(case, 10, 60)
        for month in (3, 2, 1):
            self._purchase(case.card, m, when - dt.timedelta(days=30 * month), amount)
        _auth, txn = self._purchase(case.card, m, when, amount)
        return m, txn

    def _cancel_doc(self, case: _Case, m: Merchant, dated: dt.date) -> DisputeDocument:
        return self._doc(
            case,
            "CANCELLATION_CONFIRMATION",
            "MERCHANT",
            dated,
            f"{m.name}: your subscription has been cancelled with effect from {dated}.",
        )

    def _still_charged(self, m: Merchant, txn: Transaction) -> str:
        return (
            f"I cancelled my {m.name} subscription but they charged me {eur(txn.amount_minor)} again on "
            f"{txn.transaction_at.date()}."
        )

    def c_cancelled_before_charge(self, case: _Case) -> None:
        m, txn = self._subscription(case)
        doc = self._cancel_doc(case, m, txn.transaction_at.date() - dt.timedelta(days=self.rng.randint(3, 20)))
        self._open(
            case,
            "c_cancelled_before_charge",
            txn,
            txn.amount_minor,
            self._still_charged(m, txn),
            Decision.APPROVE_PROVISIONAL_CREDIT,
            (txn.transaction_id, doc.document_id),
            "The merchant confirmed the cancellation before the disputed charge.",
        )

    def c_cancelled_after_charge(self, case: _Case) -> None:
        m, txn = self._subscription(case)
        doc = self._cancel_doc(case, m, txn.transaction_at.date() + dt.timedelta(days=self.rng.randint(1, 8)))
        self._open(
            case,
            "c_cancelled_after_charge",
            txn,
            txn.amount_minor,
            self._still_charged(m, txn),
            Decision.DECLINE,
            (txn.transaction_id, doc.document_id),
            "The cancellation took effect after the disputed charge.",
        )

    def c_no_proof(self, case: _Case) -> None:
        m, txn = self._subscription(case)
        statement = (
            f"I phoned {m.name} last month to cancel and they still charged me {eur(txn.amount_minor)}. "
            "I do not have anything in writing."
        )
        self._open(
            case,
            "c_no_proof",
            txn,
            txn.amount_minor,
            statement,
            Decision.REQUEST_MERCHANT_EVIDENCE,
            (txn.transaction_id,),
            "No proof of cancellation either way; ask the merchant for its cancellation records.",
        )

    # Amount mismatch --------------------------------------------------------------------------------------------

    def a_charged_above_receipt(self, case: _Case) -> None:
        m = self._merchant(lambda m: m.category in (catalog.ELECTRONICS_ONLINE, catalog.GROCERY, catalog.FUEL))
        agreed = self._amount(m)
        charged = agreed + agreed * self.rng.randint(10, 60) // 100
        auth, txn = self._purchase(case.card, m, self._in_time(case), agreed, posted_amount=charged)
        receipt = self._doc(
            case, "RECEIPT", "MERCHANT", txn.transaction_at.date(), f"Receipt from {m.name}: total {eur(agreed)}."
        )
        self._open(
            case,
            "a_charged_above_receipt",
            txn,
            charged - agreed,
            f"My receipt from {m.name} says {eur(agreed)} but I was charged {eur(charged)}.",
            Decision.APPROVE_PROVISIONAL_CREDIT,
            (txn.transaction_id, auth.auth_id, receipt.document_id),
            "Posted amount exceeds both the receipt and the authorised amount; credit the difference.",
        )

    def a_tip_within_tolerance(self, case: _Case) -> None:
        m = self._merchant(lambda m: m.category == catalog.RESTAURANT, prefer_city=case.holder.city)
        agreed = self._amount(m)
        charged = agreed + agreed * self.rng.randint(5, policy.TIP_TOLERANCE_PERCENT) // 100
        auth, txn = self._purchase(case.card, m, self._in_time(case), agreed, posted_amount=charged)
        self._open(
            case,
            "a_tip_within_tolerance",
            txn,
            charged - agreed,
            f"The bill at {m.name} was {eur(agreed)} but I was charged {eur(charged)}.",
            Decision.DECLINE,
            (txn.transaction_id, auth.auth_id),
            f"Restaurant tip adjustment within {policy.TIP_TOLERANCE_PERCENT}% of the authorised amount.",
        )

    def a_no_receipt(self, case: _Case) -> None:
        m = self._merchant(lambda m: m.category in (catalog.ELECTRONICS_ONLINE, catalog.TRAVEL))
        charged = self._amount(m)
        quoted = charged - charged * self.rng.randint(10, 40) // 100
        _auth, txn = self._purchase(case.card, m, self._in_time(case), charged)
        self._open(
            case,
            "a_no_receipt",
            txn,
            charged - quoted,
            f"{m.name} quoted me {eur(quoted)} but charged {eur(charged)}. I do not have the quote.",
            Decision.REQUEST_MERCHANT_EVIDENCE,
            (txn.transaction_id,),
            "Authorised and posted amounts agree and there is no receipt; ask the merchant for the order details.",
        )


def _s(name: str, category: Category, difficulty: Difficulty) -> _Scenario:
    return _Scenario(name, category, difficulty, getattr(_Generator, name))


_U, _D, _G, _C, _A = (
    Category.UNAUTHORISED_TRANSACTION,
    Category.DUPLICATE_CHARGE,
    Category.GOODS_NOT_RECEIVED,
    Category.CANCELLED_RECURRING,
    Category.AMOUNT_MISMATCH,
)
_E, _M, _H = Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD

SCENARIOS = (
    _s("u_cnp_no_3ds_fraud_flag", _U, _E),
    _s("u_high_value_escalate", _U, _M),
    _s("u_chip_pin_card_present", _U, _M),
    _s("u_3ds_authenticated", _U, _H),
    _s("u_out_of_time", _U, _M),
    _s("d_same_auth_twice", _D, _E),
    _s("d_two_genuine_purchases", _D, _M),
    _s("d_already_refunded", _D, _M),
    _s("g_merchant_contacted_no_delivery", _G, _E),
    _s("g_too_early", _G, _M),
    _s("g_delivered_with_signature", _G, _M),
    _s("c_cancelled_before_charge", _C, _E),
    _s("c_cancelled_after_charge", _C, _M),
    _s("c_no_proof", _C, _M),
    _s("a_charged_above_receipt", _A, _E),
    _s("a_tip_within_tolerance", _A, _H),
    _s("a_no_receipt", _A, _M),
)

SCENARIO_BY_NAME = {s.name: s for s in SCENARIOS}
