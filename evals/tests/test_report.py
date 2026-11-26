"""REPORT.md is what the committed run files give (ADR 0009, section 5), and every failure is explained."""

import json
import shutil
from pathlib import Path

import pytest

from evaltools import report


def test_the_committed_report_is_the_generators_output() -> None:
    assert report.OUT.read_text(encoding="utf-8") == report.build(), (
        "REPORT.md is stale: run `uv run python -m evaltools.report`"
    )


def test_every_failure_of_the_reported_run_is_explained_and_no_entry_is_stale() -> None:
    assert report.unexplained_failures() == []


def test_a_failure_without_an_entry_stops_the_build(tmp_path: Path) -> None:
    for part in ("live", "rubric", "report", "fixtures"):
        shutil.copytree(report.ROOT / part, tmp_path / part)
    failures = tmp_path / "report" / "failures.json"
    entries = json.loads(failures.read_text(encoding="utf-8"))
    dropped = sorted(entries)[0]
    del entries[dropped]
    failures.write_text(json.dumps(entries), encoding="utf-8")

    assert report.unexplained_failures(tmp_path) == [dropped]
    with pytest.raises(ValueError, match=dropped):
        report.build(tmp_path)


def test_failures_collects_checks_unsafe_proposals_and_rubric_criteria() -> None:
    grades = [
        {
            "dispute_id": "DSP-1",
            "checks": {"answered": "pass", "decision_correct": "fail"},
            "notes": ["n"],
            "unsafe": "passed_guard",
        }
    ]
    scores = {c: {"verdict": "pass", "reason": "r"} for c in report.rubric.CRITERIA}
    answers = [
        {"dispute_id": "DSP-2", "scores": scores | {"faithful": {"verdict": "fail", "reason": "why"}}},
        {"dispute_id": "DSP-3", "scores": None, "problem": "refusal"},
    ]
    assert report.failures(grades, answers) == [
        ("DSP-1", "decision_correct", "n"),
        ("DSP-1", "unsafe_passed_guard", "n"),
        ("DSP-2", "faithful", "why"),
        ("DSP-3", "rubric_unscored", "refusal"),
    ]
