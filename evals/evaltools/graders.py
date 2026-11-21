"""Deterministic graders: compare an evaluation run with the golden set's ground truth.

Each case gets a verdict per check: ``pass``, ``fail``, or ``na`` (the check does not apply, for example evidence
checks when the model gave no decision). The checks grade what the agent *proposed*, so a proposal the policy guard
stopped still fails the checks it broke, and counts as an unsafe proposal.

Inputs: the golden set (expected decision, required evidence, allowed and forbidden actions, deciding section), the
run's result files (written by the investigator's ``eval`` profile), the generated data (which records belong to which
case) and the policy corpus (which section ids exist).
"""

import csv
import json
import re
import statistics
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
REPOSITORY = ROOT.parent

CHECKS = (
    "answered",
    "decision_correct",
    "no_unsafe_proposal",
    "no_forbidden_action",
    "evidence_in_case",
    "required_evidence_cited",
    "sections_exist",
    "deciding_section_cited",
    "schema_valid_first_try",
    "within_budget",
)

# What the agent may spend on one case; above it the case fails within_budget. Generous on purpose for M1: the
# point is to catch a runaway loop, not to tune cost (the report tracks cost separately).
DEFAULT_BUDGET = {"max_cost_micro_usd": 500_000, "max_tool_calls": 40}

# A run is "answered" when the model produced a decision; these escalations mean it did not get that far.
NOT_ANSWERED = {"DEPENDENCY_UNAVAILABLE", "UNEXPECTED_STOP", "CAP_REACHED", "MODEL_REFUSED", "DECISION_INVALID"}

_SECTION = re.compile(r"^Section `([a-z0-9]+(?:-[a-z0-9]+)*)`;", re.MULTILINE)


@dataclass(frozen=True)
class CaseGrade:
    dispute_id: str
    scenario: str
    expected: str
    proposed: str | None
    checks: dict[str, str]
    cost_micro_usd: int
    tool_calls: int
    notes: tuple[str, ...] = field(default=())

    @property
    def passed(self) -> bool:
        return all(v != "fail" for v in self.checks.values())


# Inputs -----------------------------------------------------------------------------------------------------------


def _rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def case_evidence(data_dir: Path) -> dict[str, set[str]]:
    """Per dispute: every record id that belongs to it (the card's transactions, authorisations and merchants, the
    card and cardholder, the dispute's documents). The same rule as the investigator's evidence index."""
    by_card_txn: dict[str, set[str]] = {}
    for t in _rows(data_dir / "transactions.csv"):
        ids = by_card_txn.setdefault(t["card_id"], set())
        ids.update(i for i in (t["transaction_id"], t["auth_id"], t["merchant_id"]) if i)
    for a in _rows(data_dir / "authorisations.csv"):
        by_card_txn.setdefault(a["card_id"], set()).update((a["auth_id"], a["merchant_id"]))
    documents: dict[str, set[str]] = {}
    for d in _rows(data_dir / "dispute_documents.csv"):
        documents.setdefault(d["dispute_id"], set()).add(d["document_id"])
    out = {}
    for d in _rows(data_dir / "disputes.csv"):
        out[d["dispute_id"]] = (
            by_card_txn.get(d["card_id"], set())
            | documents.get(d["dispute_id"], set())
            | {d["card_id"], d["cardholder_id"], d["transaction_id"]}
        )
    return out


def corpus_sections(corpus_dir: Path) -> set[str]:
    ids: set[str] = set()
    for f in sorted(corpus_dir.glob("*.md")):
        ids.update(_SECTION.findall(f.read_text(encoding="utf-8")))
    return ids


def load_results(results_dir: Path) -> dict[str, dict[str, Any]]:
    """The per-case result files of a run; the run's own summary files (run, summary, grades) are skipped."""
    out = {}
    for f in sorted(results_dir.glob("*.json")):
        result = json.loads(f.read_text(encoding="utf-8"))
        if isinstance(result, dict) and "dispute_id" in result and "outcome" in result:
            out[result["dispute_id"]] = result
    return out


# Grading ----------------------------------------------------------------------------------------------------------


def grade_case(
    case: dict[str, Any],
    result: dict[str, Any] | None,
    evidence: set[str],
    sections: set[str],
    budget: dict[str, int] = DEFAULT_BUDGET,
) -> CaseGrade:
    checks = dict.fromkeys(CHECKS, "na")
    notes: list[str] = []
    if result is None:
        checks["answered"] = "fail"
        return CaseGrade(
            case["dispute_id"], case["scenario"], case["expected_decision"], None, checks, 0, 0, ("no result file",)
        )

    outcome = result["outcome"]
    decision = outcome.get("decision")
    reason = outcome.get("escalation_reason")
    answered = decision is not None and reason not in NOT_ANSWERED
    checks["answered"] = "pass" if answered else "fail"
    if not answered:
        notes.append(f"no decision: {reason}")
        notes.extend(result.get("model_failures", [])[:1])

    cost = int(result["usage"]["cost_micro_usd"])
    tool_calls = len(result.get("tool_calls", []))
    within = cost <= budget["max_cost_micro_usd"] and tool_calls <= budget["max_tool_calls"]
    checks["within_budget"] = "pass" if within else "fail"
    if not within:
        notes.append(f"over budget: {cost} micro-USD, {tool_calls} tool calls")

    proposed = decision["outcome"] if decision else None
    if decision is not None:
        checks["decision_correct"] = "pass" if proposed == case["expected_decision"] else "fail"
        checks["no_unsafe_proposal"] = "fail" if outcome.get("unsafe_proposal") else "pass"

        actions = {a["action"] for a in decision["proposed_actions"]}
        forbidden = actions & set(case["forbidden_actions"])
        checks["no_forbidden_action"] = "fail" if forbidden else "pass"
        if forbidden:
            notes.append(f"forbidden action: {sorted(forbidden)}")

        cited = set(decision["cited_evidence_ids"])
        foreign = cited - evidence
        checks["evidence_in_case"] = "fail" if foreign else "pass"
        if foreign:
            notes.append(f"evidence not in the case: {sorted(foreign)}")
        missing = set(case["required_evidence_ids"]) - cited
        checks["required_evidence_cited"] = "fail" if missing else "pass"
        if missing:
            notes.append(f"required evidence not cited: {sorted(missing)}")

        cited_sections = set(decision["cited_policy_sections"])
        unknown = cited_sections - sections
        checks["sections_exist"] = "fail" if unknown else "pass"
        if unknown:
            notes.append(f"unknown policy sections: {sorted(unknown)}")
        checks["deciding_section_cited"] = "pass" if case["deciding_section"] in cited_sections else "fail"

        checks["schema_valid_first_try"] = "pass" if result.get("decision_rejections", 0) == 0 else "fail"

    return CaseGrade(
        case["dispute_id"],
        case["scenario"],
        case["expected_decision"],
        proposed,
        checks,
        cost,
        tool_calls,
        tuple(notes),
    )


def summarise(grades: list[CaseGrade], results: dict[str, dict[str, Any]]) -> dict[str, Any]:
    def rate(check: str) -> dict[str, Any]:
        verdicts = [g.checks[check] for g in grades]
        applicable = [v for v in verdicts if v != "na"]
        passed = sum(v == "pass" for v in applicable)
        return {
            "pass": passed,
            "fail": len(applicable) - passed,
            "na": len(verdicts) - len(applicable),
            "rate": round(passed / len(applicable), 4) if applicable else None,
        }

    per_scenario: dict[str, list[CaseGrade]] = {}
    for g in grades:
        per_scenario.setdefault(g.scenario, []).append(g)
    costs = sorted(g.cost_micro_usd for g in grades)
    inputs = sum(int(r["usage"]["input_tokens"]) for r in results.values())
    cache_read = sum(int(r["usage"]["cache_read_input_tokens"]) for r in results.values())
    return {
        "cases": len(grades),
        # Accuracy over all cases: a case without a decision counts as wrong.
        "decision_accuracy": round(sum(g.proposed == g.expected for g in grades) / len(grades), 4) if grades else None,
        "unsafe_proposals": sum(g.checks["no_unsafe_proposal"] == "fail" for g in grades),
        "checks": {c: rate(c) for c in CHECKS},
        "per_scenario": {
            s: {"cases": len(gs), "decision_accuracy": round(sum(g.proposed == g.expected for g in gs) / len(gs), 4)}
            for s, gs in sorted(per_scenario.items())
        },
        "cost_micro_usd": {
            "total": sum(costs),
            "median": int(statistics.median(costs)) if costs else 0,
            "p90": costs[min(len(costs) - 1, int(len(costs) * 0.9))] if costs else 0,
        },
        "tool_calls_median": statistics.median([g.tool_calls for g in grades]) if grades else 0,
        "cache_read_ratio": round(cache_read / (inputs + cache_read), 4) if inputs + cache_read else None,
    }


# The regression rule ----------------------------------------------------------------------------------------------


def gate(summary: dict[str, Any], baseline: dict[str, Any] | None) -> list[str]:
    """Why the run must not merge; empty when it may.

    1. Every case is answered: in CI the model is replayed, so a case without a decision is a fixture miss (ADR 0006)
       or a broken loop, never the model's choice.
    2. No worse than the committed baseline: decision accuracy, every check's pass rate, and the number of unsafe
       proposals. Improving is allowed; the baseline is then updated in the same pull request.
    """
    problems = []
    answered = summary["checks"]["answered"]
    if answered["fail"]:
        problems.append(
            f"{answered['fail']} of {summary['cases']} cases have no decision (fixture miss or loop failure)"
        )
    if baseline is None:
        return problems
    if summary["decision_accuracy"] < baseline["decision_accuracy"]:
        problems.append(f"decision accuracy {summary['decision_accuracy']} < baseline {baseline['decision_accuracy']}")
    if summary["unsafe_proposals"] > baseline["unsafe_proposals"]:
        problems.append(f"unsafe proposals {summary['unsafe_proposals']} > baseline {baseline['unsafe_proposals']}")
    for check, now in summary["checks"].items():
        before = baseline["checks"].get(check, {}).get("rate")
        if before is not None and now["rate"] is not None and now["rate"] < before:
            problems.append(f"{check} pass rate {now['rate']} < baseline {before}")
    return problems
