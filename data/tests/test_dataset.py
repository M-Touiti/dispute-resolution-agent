import datetime as dt
import re
from collections import Counter

import pytest

from datagen import policy
from datagen.generator import SCENARIOS, Config, generate
from datagen.model import Adversarial, Decision
from datagen.writers import FOREIGN_KEYS, TABLES, columns


@pytest.fixture(scope="module")
def index(dataset):
    return {
        "cardholders": {r.cardholder_id: r for r in dataset.cardholders},
        "cards": {r.card_id: r for r in dataset.cards},
        "merchants": {r.merchant_id: r for r in dataset.merchants},
        "authorisations": {r.auth_id: r for r in dataset.authorisations},
        "transactions": {r.transaction_id: r for r in dataset.transactions},
        "disputes": {r.dispute_id: r for r in dataset.disputes},
        "documents": {r.document_id: r for r in dataset.dispute_documents},
        "truth": {r.dispute_id: r for r in dataset.ground_truth},
    }


def test_primary_keys_are_unique(dataset):
    for table in TABLES:
        key = columns(table.row_type)[0]
        ids = [getattr(r, key) for r in getattr(dataset, table.name)]
        assert len(ids) == len(set(ids)), table.name


def test_foreign_keys_resolve(dataset):
    tables = {t.name: t for t in TABLES}
    for (table_name, column), ref in FOREIGN_KEYS.items():
        ref_table = tables[ref.split(".")[1]]
        ref_key = columns(ref_table.row_type)[0]
        targets = {getattr(r, ref_key) for r in getattr(dataset, ref_table.name)}
        for row in getattr(dataset, table_name):
            value = getattr(row, column)
            assert value is None or value in targets, f"{table_name}.{column}={value}"


def test_every_scenario_is_generated_evenly(dataset):
    counts = Counter(t.scenario for t in dataset.ground_truth if t.adversarial == Adversarial.NONE)
    assert set(counts) == {s.name for s in SCENARIOS}
    assert max(counts.values()) - min(counts.values()) <= 1


def test_every_dispute_has_exactly_one_ground_truth(dataset):
    assert sorted(d.dispute_id for d in dataset.disputes) == sorted(t.dispute_id for t in dataset.ground_truth)


def test_disputed_transaction_belongs_to_the_card_and_precedes_the_dispute(dataset, index):
    for d in dataset.disputes:
        txn = index["transactions"][d.transaction_id]
        assert txn.card_id == d.card_id
        assert index["cards"][d.card_id].cardholder_id == d.cardholder_id
        assert txn.transaction_at < d.opened_at
        if index["truth"][d.dispute_id].adversarial != Adversarial.OVERCLAIM:  # an overclaim may claim more
            assert 0 < d.claimed_amount_minor <= txn.amount_minor


def test_required_evidence_exists_and_belongs_to_the_case(dataset, index):
    for t in dataset.ground_truth:
        dispute = index["disputes"][t.dispute_id]
        assert t.required_evidence_ids, t.dispute_id
        assert dispute.transaction_id in t.required_evidence_ids
        for evidence_id in t.required_evidence_ids:
            if evidence_id.startswith("DOC-"):
                assert index["documents"][evidence_id].dispute_id == t.dispute_id
            elif evidence_id.startswith("TXN-"):
                assert index["transactions"][evidence_id].card_id == dispute.card_id
            elif evidence_id.startswith("AUTH-"):
                assert index["authorisations"][evidence_id].card_id == dispute.card_id
            else:
                pytest.fail(f"unknown evidence id {evidence_id}")


def test_actions_follow_the_decision(dataset):
    for t in dataset.ground_truth:
        assert set(t.allowed_actions).isdisjoint(t.forbidden_actions)
        assert sorted(t.allowed_actions + t.forbidden_actions) == sorted(policy.ACTIONS)
        if t.expected_decision != Decision.APPROVE_PROVISIONAL_CREDIT:
            assert "issue_provisional_credit" in t.forbidden_actions


def _age_days(dispute, txn) -> int:
    return (dispute.opened_at.date() - txn.posted_on).days


def test_time_limit_decides_only_the_out_of_time_scenario(dataset, index):
    for t in dataset.ground_truth:
        d = index["disputes"][t.dispute_id]
        age = _age_days(d, index["transactions"][d.transaction_id])
        if t.scenario == "u_out_of_time":
            assert age > policy.TIME_LIMIT_DAYS
            assert t.expected_decision == Decision.DECLINE
        else:
            assert age <= policy.TIME_LIMIT_DAYS, t.dispute_id


def test_provisional_credit_limit(dataset, index):
    for t in dataset.ground_truth:
        claimed = index["disputes"][t.dispute_id].claimed_amount_minor
        if t.expected_decision == Decision.APPROVE_PROVISIONAL_CREDIT:
            assert 0 < t.credit_ceiling_minor <= policy.PROVISIONAL_CREDIT_LIMIT_MINOR
            if t.adversarial == Adversarial.NONE:
                assert claimed <= policy.PROVISIONAL_CREDIT_LIMIT_MINOR
        else:
            assert t.credit_ceiling_minor == 0
        if t.scenario == "u_high_value_escalate":
            assert claimed > policy.PROVISIONAL_CREDIT_LIMIT_MINOR
            assert t.expected_decision == Decision.ESCALATE


def test_unauthorised_signals_match_the_scenario(dataset, index):
    for t in dataset.ground_truth:
        d = index["disputes"][t.dispute_id]
        auth = index["authorisations"].get(index["transactions"][d.transaction_id].auth_id)
        if t.scenario == "u_cnp_no_3ds_fraud_flag":
            assert (auth.entry_mode, auth.three_ds_result, auth.fraud_flag) == ("ECOMMERCE", "NOT_PERFORMED", True)
        if t.scenario == "u_chip_pin_card_present":
            assert (auth.entry_mode, auth.pin_verified, auth.fraud_flag) == ("CHIP", True, False)
        if t.scenario == "u_3ds_authenticated":
            assert (auth.three_ds_result, auth.fraud_flag) == ("AUTHENTICATED", False)


def test_goods_not_received_wait(dataset, index):
    expected_re = re.compile(r"expected delivery (\d{4}-\d{2}-\d{2})")
    for t in dataset.ground_truth:
        if not t.scenario.startswith("g_"):
            continue
        d = index["disputes"][t.dispute_id]
        orders = [
            doc
            for doc in dataset.dispute_documents
            if doc.dispute_id == d.dispute_id and doc.kind == "ORDER_CONFIRMATION"
        ]
        assert len(orders) == 1
        expected = dt.date.fromisoformat(expected_re.search(orders[0].summary).group(1))
        waited = (d.opened_at.date() - expected).days
        if t.scenario == "g_too_early":
            assert waited < policy.GOODS_NOT_RECEIVED_WAIT_DAYS
        else:
            assert waited >= policy.GOODS_NOT_RECEIVED_WAIT_DAYS


def test_tip_scenario_stays_within_tolerance(dataset, index):
    for t in dataset.ground_truth:
        if t.scenario != "a_tip_within_tolerance":
            continue
        txn = index["transactions"][index["disputes"][t.dispute_id].transaction_id]
        auth = index["authorisations"][txn.auth_id]
        assert auth.amount_minor < txn.amount_minor
        assert txn.amount_minor * 100 <= auth.amount_minor * (100 + policy.TIP_TOLERANCE_PERCENT)


def test_cancellation_date_decides_recurring_disputes(dataset, index):
    for t in dataset.ground_truth:
        if t.scenario not in ("c_cancelled_before_charge", "c_cancelled_after_charge"):
            continue
        d = index["disputes"][t.dispute_id]
        charge = index["transactions"][d.transaction_id].transaction_at.date()
        (cancel,) = [
            doc
            for doc in dataset.dispute_documents
            if doc.dispute_id == d.dispute_id and doc.kind == "CANCELLATION_CONFIRMATION"
        ]
        assert (cancel.dated < charge) == (t.scenario == "c_cancelled_before_charge")


def test_no_card_number_shaped_values(written):
    out, _ = written
    pan = re.compile(rb"(?<!\d)\d{13,19}(?!\d)")
    for path in out.iterdir():
        assert not pan.search(path.read_bytes()), path.name


def test_ground_truth_never_reaches_the_sql_seed(written):
    out, _ = written
    sql = (out / "seed.sql").read_text(encoding="utf-8")
    for leak in (
        ".ground_truth",
        "expected_decision",
        "required_evidence_ids",
        "forbidden_actions",
        "scenario",
        "adversarial",
        "OVERCLAIM",
        "injected_",
        "credit_ceiling",
    ):
        assert leak not in sql


def test_rejects_more_disputes_than_cards():
    with pytest.raises(ValueError, match="exceeds cards"):
        generate(Config(seed=1, cardholders=5, disputes=50))


def test_no_active_card_is_expired_and_no_transaction_follows_its_cards_expiry(dataset, config):
    as_of_month = config.as_of.strftime("%Y-%m")
    cards = {c.card_id: c for c in dataset.cards}
    for c in dataset.cards:
        assert c.expiry > as_of_month or c.status != "ACTIVE", c
    late = [t.transaction_id for t in dataset.transactions if t.posted_on.strftime("%Y-%m") > cards[t.card_id].expiry]
    assert not late, late[:5]


def test_a_cardholder_who_says_never_shopped_there_has_not(dataset):
    for d in dataset.disputes:
        if "never shopped there" not in d.cardholder_statement:
            continue
        txn = next(t for t in dataset.transactions if t.transaction_id == d.transaction_id)
        earlier = [
            t.transaction_id
            for t in dataset.transactions
            if t.card_id == d.card_id and t.merchant_id == txn.merchant_id and t.transaction_at < txn.transaction_at
        ]
        assert not earlier, (d.dispute_id, earlier)
