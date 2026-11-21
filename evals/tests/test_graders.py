"""The graders on synthetic results for real golden cases (the generated data must exist: see test_golden.py)."""

import copy
import json
from pathlib import Path
from typing import Any

import pytest

from evaltools import golden, graders

GOLDEN = json.loads(golden.path_for(golden.V1).read_text(encoding="utf-8"))
SECTIONS = graders.corpus_sections(graders.REPOSITORY / "data" / "policy-corpus")


@pytest.fixture(scope="module")
def evidence() -> dict[str, set[str]]:
    if not (golden.DEFAULT_DATA / "transactions.csv").exists():
        pytest.fail("data/generated is missing: run `uv run python generate.py` in data/ first")
    return graders.case_evidence(golden.DEFAULT_DATA)


def case(scenario: str) -> dict[str, Any]:
    return next(c for c in GOLDEN["cases"] if c["scenario"] == scenario)


def action_for(c: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {
            "issue_provisional_credit": {"action": "issue_provisional_credit", "amount_minor": 1, "currency": "EUR"},
            "close_dispute": {"action": "close_dispute", "reason": "r", "policy_section_ids": [c["deciding_section"]]},
            "request_merchant_evidence": {"action": "request_merchant_evidence", "evidence": ["RECEIPT"], "note": "n"},
        }[a]
        for a in c["allowed_actions"]
    ]


def perfect(c: dict[str, Any]) -> dict[str, Any]:
    """The result a correct, clean investigation of the case would produce."""
    escalated = c["expected_decision"] == "ESCALATE"
    return {
        "dispute_id": c["dispute_id"],
        "outcome": {
            "state": "ESCALATED" if escalated else "DECIDED",
            "escalation_reason": "AGENT_DECISION" if escalated else None,
            "unsafe_proposal": False,
            "decision": {
                "outcome": c["expected_decision"],
                "confidence": "HIGH",
                "rationale": "r",
                "cited_evidence_ids": list(c["required_evidence_ids"]),
                "cited_policy_sections": [c["deciding_section"]],
                "proposed_actions": action_for(c),
            },
            "guard_violations": [],
        },
        "usage": {
            "model_calls": 3,
            "input_tokens": 3000,
            "output_tokens": 600,
            "cache_read_input_tokens": 9000,
            "cache_creation_input_tokens": 0,
            "cost_micro_usd": 30_000,
            "price_tables": ["test"],
        },
        "tool_calls": [{"server": "ledger", "tool": "get_authorisations", "is_error": False}],
        "system_tool_calls": 3,
        "skills_loaded": [],
        "decision_rejections": 0,
        "triage_fell_back": False,
        "model_failures": [],
    }


def grade(c, result, evidence):
    return graders.grade_case(c, result, evidence[c["dispute_id"]], SECTIONS)


@pytest.mark.parametrize("scenario", sorted({c["scenario"] for c in GOLDEN["cases"]}))
def test_a_perfect_answer_passes_every_check(scenario: str, evidence) -> None:
    c = case(scenario)
    g = grade(c, perfect(c), evidence)

    assert g.passed, g.notes
    assert set(g.checks.values()) == {"pass"}


def test_the_wrong_decision_fails_only_that_check(evidence) -> None:
    c = case("u_chip_pin_card_present")
    r = perfect(c)
    r["outcome"]["decision"]["outcome"] = "REQUEST_MERCHANT_EVIDENCE"

    g = grade(c, r, evidence)

    assert g.checks["decision_correct"] == "fail"
    assert [k for k, v in g.checks.items() if v == "fail"] == ["decision_correct"]


def test_evidence_from_another_dispute_and_missing_required_evidence(evidence) -> None:
    c, other = case("u_chip_pin_card_present"), case("u_3ds_authenticated")
    r = perfect(c)
    r["outcome"]["decision"]["cited_evidence_ids"] = list(other["required_evidence_ids"])

    g = grade(c, r, evidence)

    assert g.checks["evidence_in_case"] == "fail"
    assert g.checks["required_evidence_cited"] == "fail"


def test_unknown_and_missing_policy_sections(evidence) -> None:
    c = case("u_out_of_time")
    r = perfect(c)
    r["outcome"]["decision"]["cited_policy_sections"] = ["no-such-section"]

    g = grade(c, r, evidence)

    assert g.checks["sections_exist"] == "fail"
    assert g.checks["deciding_section_cited"] == "fail"


def test_a_forbidden_action_and_an_unsafe_proposal(evidence) -> None:
    c = case("u_high_value_escalate")
    r = perfect(c)
    r["outcome"] |= {"escalation_reason": "GUARD_FAILED", "unsafe_proposal": True}
    r["outcome"]["decision"] |= {
        "outcome": "APPROVE_PROVISIONAL_CREDIT",
        "proposed_actions": [{"action": "issue_provisional_credit", "amount_minor": 999_999, "currency": "EUR"}],
    }

    g = grade(c, r, evidence)

    assert g.checks["no_forbidden_action"] == "fail"
    assert g.checks["no_unsafe_proposal"] == "fail"
    assert g.checks["decision_correct"] == "fail"


def test_no_decision_is_unanswered_and_the_other_checks_do_not_apply(evidence) -> None:
    c = case("u_cnp_no_3ds_fraud_flag")
    r = perfect(c)
    r["outcome"] |= {"state": "ESCALATED", "escalation_reason": "DEPENDENCY_UNAVAILABLE", "decision": None}
    r["model_failures"] = ["INVESTIGATE: FIXTURE_MISSING: no fixture x.json"]

    g = grade(c, r, evidence)

    assert g.checks["answered"] == "fail"
    assert g.checks["decision_correct"] == "na"
    assert any("FIXTURE_MISSING" in n for n in g.notes)


def test_a_schema_retry_and_a_runaway_loop(evidence) -> None:
    c = case("u_3ds_authenticated")
    r = perfect(c)
    r["decision_rejections"] = 1
    r["usage"]["cost_micro_usd"] = 2_000_000

    g = grade(c, r, evidence)

    assert g.checks["schema_valid_first_try"] == "fail"
    assert g.checks["within_budget"] == "fail"


# Summary and the regression rule --------------------------------------------------------------------------------


def run(evidence, mutate=None) -> dict[str, Any]:
    results = {c["dispute_id"]: perfect(c) for c in GOLDEN["cases"]}
    if mutate:
        mutate(results)
    grades = [grade(c, results[c["dispute_id"]], evidence) for c in GOLDEN["cases"]]
    return graders.summarise(grades, results)


def test_a_perfect_run_summarises_to_full_marks(evidence) -> None:
    s = run(evidence)

    assert s["cases"] == 30
    assert s["decision_accuracy"] == 1.0
    assert s["unsafe_proposals"] == 0
    assert {k: v["rate"] for k, v in s["checks"].items()} == dict.fromkeys(graders.CHECKS, 1.0)
    assert set(s["per_scenario"]) == {c["scenario"] for c in GOLDEN["cases"]}
    assert s["cost_micro_usd"]["total"] == 30 * 30_000
    assert s["cache_read_ratio"] == 0.75


def test_the_gate_passes_an_equal_run_and_names_every_regression(evidence) -> None:
    baseline = run(evidence)

    def one_wrong_and_one_unsafe(results):
        first, second = list(results.values())[:2]
        first["outcome"]["decision"]["outcome"] = (
            "DECLINE" if first["outcome"]["decision"]["outcome"] != "DECLINE" else "ESCALATE"
        )
        second["outcome"]["unsafe_proposal"] = True

    worse = run(evidence, one_wrong_and_one_unsafe)

    assert graders.gate(copy.deepcopy(baseline), baseline) == []
    problems = graders.gate(worse, baseline)
    assert any(p.startswith("decision accuracy") for p in problems)
    assert any(p.startswith("unsafe proposals 1 > baseline 0") for p in problems)
    assert any(p.startswith("decision_correct pass rate") for p in problems)


def test_without_a_baseline_only_unanswered_cases_block(evidence) -> None:
    def miss(results):
        r = next(iter(results.values()))
        r["outcome"] |= {"escalation_reason": "DEPENDENCY_UNAVAILABLE", "decision": None}

    assert graders.gate(run(evidence), None) == []
    assert graders.gate(run(evidence, miss), None) == ["1 of 30 cases have no decision (fixture miss or loop failure)"]


def test_the_cli_grades_a_run_directory_and_writes_the_summary(tmp_path: Path, evidence) -> None:
    from evaltools import grade as cli

    for c in GOLDEN["cases"]:
        (tmp_path / f"{c['dispute_id']}.json").write_text(json.dumps(perfect(c)), encoding="utf-8")
    baseline = tmp_path / "baseline" / "unauthorised-v1.json"

    assert cli.main(["--results", str(tmp_path), "--baseline", str(baseline), "--write-baseline"]) == 0
    assert cli.main(["--results", str(tmp_path), "--baseline", str(baseline)]) == 0
    assert json.loads((tmp_path / "summary.json").read_text())["decision_accuracy"] == 1.0
