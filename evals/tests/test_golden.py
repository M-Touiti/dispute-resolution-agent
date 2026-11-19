"""The committed golden set is exactly what the selector produces from the seeded dataset.

Needs ``data/generated`` (``uv run python generate.py`` in ``data/``). A missing dataset fails; it never skips, so
CI cannot pass without checking the golden set.
"""

import csv
import json
import random
from collections import Counter
from pathlib import Path

import pytest

from evaltools import golden

COMMITTED = golden.path_for(golden.V1)


@pytest.fixture(scope="module")
def data_dir() -> Path:
    if not (golden.DEFAULT_DATA / "manifest.json").exists():
        pytest.fail("data/generated is missing: run `uv run python generate.py` in data/ first")
    return golden.DEFAULT_DATA


@pytest.fixture(scope="module")
def committed() -> dict:
    return json.loads(COMMITTED.read_text(encoding="utf-8"))


def test_committed_golden_set_matches_the_selector_byte_for_byte(data_dir: Path) -> None:
    expected = golden.render(golden.select(data_dir, golden.V1))
    assert COMMITTED.read_text(encoding="utf-8") == expected, (
        "golden set is stale: run `uv run python -m evaltools.golden`, review the diff, and re-record fixtures"
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
