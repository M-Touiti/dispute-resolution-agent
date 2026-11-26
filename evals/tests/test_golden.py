"""The committed golden sets (and v2's slices) are exactly what the selector produces from the seeded dataset.

Needs ``data/generated`` (``uv run python generate.py`` in ``data/``). A missing dataset fails; it never skips, so
CI cannot pass without checking the golden set.
"""

import csv
import datetime as dt
import json
import random
from collections import Counter
from pathlib import Path

import pytest

from evaltools import golden

COMMITTED = golden.path_for(golden.V1)
COMMITTED_V2 = golden.path_for(golden.V2)
COMMITTED_SLICES = golden.slices_path_for(golden.V2)
COMMITTED_PILOT = golden.pilot_path_for(golden.V2)


@pytest.fixture(scope="module")
def data_dir() -> Path:
    if not (golden.DEFAULT_DATA / "manifest.json").exists():
        pytest.fail("data/generated is missing: run `uv run python generate.py` in data/ first")
    return golden.DEFAULT_DATA


@pytest.fixture(scope="module")
def committed() -> dict:
    return json.loads(COMMITTED.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def v2() -> dict:
    return json.loads(COMMITTED_V2.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def v2_slices() -> dict:
    return json.loads(COMMITTED_SLICES.read_text(encoding="utf-8"))


def test_committed_golden_files_match_the_selector_byte_for_byte(data_dir: Path) -> None:
    expected = golden.outputs(data_dir)
    assert set(expected) == {COMMITTED, COMMITTED_V2, COMMITTED_SLICES, COMMITTED_PILOT}
    for path, text in expected.items():
        assert path.read_text(encoding="utf-8") == text, (
            f"{path.name} is stale: run `uv run python -m evaltools.golden`, review the diff, and re-record fixtures"
        )


def test_v1_has_six_cases_for_each_unauthorised_scenario(committed: dict) -> None:
    cases = committed["cases"]
    assert len(cases) == 30
    assert {c["category"] for c in cases} == {"UNAUTHORISED_TRANSACTION"}
    assert Counter(c["scenario"] for c in cases) == {
        "u_cnp_no_3ds_fraud_flag": 6,
        "u_high_value_escalate": 6,
        "u_chip_pin_card_present": 6,
        "u_3ds_authenticated": 6,
        "u_out_of_time": 6,
    }


def test_v1_covers_three_decisions(committed: dict) -> None:
    assert Counter(c["expected_decision"] for c in committed["cases"]) == {
        "APPROVE_PROVISIONAL_CREDIT": 6,
        "ESCALATE": 6,
        "DECLINE": 18,
    }


def test_every_case_is_complete(committed: dict) -> None:
    ids = [c["dispute_id"] for c in committed["cases"]]
    assert ids == sorted(set(ids))
    for c in committed["cases"]:
        assert c["required_evidence_ids"], c["dispute_id"]
        assert c["deciding_section"], c["dispute_id"]
        assert not set(c["allowed_actions"]) & set(c["forbidden_actions"]), c["dispute_id"]


def test_dataset_fingerprint_matches_the_manifest(committed: dict, data_dir: Path) -> None:
    manifest = json.loads((data_dir / "manifest.json").read_text(encoding="utf-8"))
    assert committed["dataset"]["generator_version"] == manifest["generator_version"]
    assert committed["dataset"]["ground_truth_sha256"] == manifest["sha256"]["ground_truth.csv"]
    assert committed["dataset"]["seed_sql_sha256"] == manifest["sha256"]["seed.sql"]


def test_selection_does_not_depend_on_row_order(data_dir: Path, tmp_path: Path) -> None:
    for name in ("manifest.json",):
        (tmp_path / name).write_bytes((data_dir / name).read_bytes())
    with (data_dir / "ground_truth.csv").open(encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header, rows = next(reader), list(reader)
    random.Random(7).shuffle(rows)
    with (tmp_path / "ground_truth.csv").open("w", encoding="utf-8", newline="") as f:
        csv.writer(f, lineterminator="\n").writerows([header, *rows])

    assert golden.select(tmp_path, golden.V1)["cases"] == golden.select(data_dir, golden.V1)["cases"]


def test_a_larger_selection_keeps_every_earlier_pick(data_dir: Path) -> None:
    v1 = golden.select(data_dir, golden.V1)
    bigger = golden.Selection("bigger", "", golden.V1.categories, golden.V1.per_scenario + 3, golden.V1.salt)
    grown = golden.select(data_dir, bigger)

    assert {c["dispute_id"] for c in v1["cases"]} <= {c["dispute_id"] for c in grown["cases"]}


# Golden set v2 (ADR 0009) -----------------------------------------------------------------------------------------


def test_v2_has_eight_cases_per_scenario_and_twelve_per_adversarial_kind(v2: dict) -> None:
    cases = v2["cases"]
    assert len(cases) == 160
    plain = [c for c in cases if c["adversarial"] == "NONE"]
    per_scenario = Counter(c["scenario"] for c in plain)
    assert len(per_scenario) == 17 and set(per_scenario.values()) == {8}
    assert {c["category"] for c in plain} == set(golden.CATEGORIES)
    assert Counter(c["adversarial"] for c in cases if c["adversarial"] != "NONE") == {"INJECTION": 12, "OVERCLAIM": 12}


def test_v2_keeps_every_case_of_v1_unchanged(committed: dict, v2: dict) -> None:
    v2_cases = {c["dispute_id"]: c for c in v2["cases"]}
    for case in committed["cases"]:
        grown = v2_cases[case["dispute_id"]]
        assert {k: grown[k] for k in case} == case
        assert grown["adversarial"] == "NONE"


def test_v2_cases_carry_the_adversarial_fields(v2: dict) -> None:
    for c in v2["cases"]:
        approve = c["expected_decision"] == "APPROVE_PROVISIONAL_CREDIT"
        assert (c["credit_ceiling_minor"] > 0) == approve, c["dispute_id"]
        goal = c["injected_goal"]
        if c["adversarial"] == "INJECTION":
            assert goal["outcome"] != c["expected_decision"]
            assert goal["action"] in c["forbidden_actions"]
            assert (goal["amount_minor"] is not None) == (goal["action"] == "issue_provisional_credit")
        else:
            assert goal is None


def test_slices_cover_every_case_and_hold_every_stratum(v2: dict, v2_slices: dict) -> None:
    cases = {c["dispute_id"]: c for c in v2["cases"]}
    slices = v2_slices["slices"]
    assert len(slices) == 6
    assert {d for s in slices for d in s} == set(cases)
    for s in slices:
        assert len(s) == len(set(s)) == golden.SLICE_SIZE
        kinds = Counter(cases[d]["adversarial"] for d in s)
        assert kinds["INJECTION"] >= 1 and kinds["OVERCLAIM"] >= 1
        categories = Counter(cases[d]["category"] for d in s)
        assert set(categories) == set(golden.CATEGORIES) and min(categories.values()) >= 3
    assert v2_slices["dataset"] == v2["dataset"]


def test_v2_selection_does_not_depend_on_row_order(data_dir: Path, tmp_path: Path) -> None:
    (tmp_path / "manifest.json").write_bytes((data_dir / "manifest.json").read_bytes())
    with (data_dir / "ground_truth.csv").open(encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header, rows = next(reader), list(reader)
    random.Random(11).shuffle(rows)
    with (tmp_path / "ground_truth.csv").open("w", encoding="utf-8", newline="") as f:
        csv.writer(f, lineterminator="\n").writerows([header, *rows])

    shuffled = golden.select(tmp_path, golden.V2)
    assert shuffled == golden.select(data_dir, golden.V2)
    assert golden.slices(shuffled) == golden.slices(golden.select(data_dir, golden.V2))


def test_the_slice_of_a_day_rotates_through_every_slice() -> None:
    first = dt.date(2026, 11, 26)
    seen = [golden.slice_of(first + dt.timedelta(days=i), 6) for i in range(6)]
    assert sorted(seen) == list(range(6))
    assert golden.slice_of(dt.date(1970, 1, 1), 6) == 0
    assert golden.slice_of(first, 6) == golden.slice_of(first + dt.timedelta(days=6), 6)


def test_the_pilot_covers_every_new_scenario_and_each_kind_across_categories(v2: dict) -> None:
    pilot = json.loads(COMMITTED_PILOT.read_text(encoding="utf-8"))
    by_id = {c["dispute_id"]: c for c in v2["cases"]}
    cases = pilot["cases"]
    assert all(by_id[c["dispute_id"]] == c for c in cases)
    plain = [c for c in cases if c["adversarial"] == "NONE"]
    assert "UNAUTHORISED_TRANSACTION" not in {c["category"] for c in plain}
    assert set(Counter(c["scenario"] for c in plain).values()) == {3}
    assert len(Counter(c["scenario"] for c in plain)) == 12
    for kind in ("INJECTION", "OVERCLAIM"):
        of_kind = [c for c in cases if c["adversarial"] == kind]
        assert len(of_kind) == 4
        assert len({c["category"] for c in of_kind}) == 4
