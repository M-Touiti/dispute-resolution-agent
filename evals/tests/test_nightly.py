"""The nightly slice selection, its job summary and the files kept on eval-nightly."""

import datetime as dt
import json
import shutil
from pathlib import Path

import pytest

from evaltools import golden, nightly

SLICES = json.loads(nightly.SLICES.read_text(encoding="utf-8"))["slices"]
RECORDING = nightly.ROOT / "live" / "recording"


def test_the_day_picks_its_slice_and_every_case_carries_its_golden_fields() -> None:
    day = dt.date(2026, 12, 1)
    selected = nightly.select(day)
    index = golden.slice_of(day, len(SLICES))
    assert selected["nightly"] == {"date": "2026-12-01", "slice": index, "slices": 6, "limit": None}
    assert [c["dispute_id"] for c in selected["cases"]] == sorted(SLICES[index])
    v2 = {c["dispute_id"]: c for c in json.loads(nightly.GOLDEN.read_text(encoding="utf-8"))["cases"]}
    assert all(v2[c["dispute_id"]] == c for c in selected["cases"])
    assert nightly.select(day + dt.timedelta(days=1))["nightly"]["slice"] == (index + 1) % 6


def test_a_limit_keeps_the_slices_first_cases_and_must_be_positive() -> None:
    day = dt.date(2026, 12, 1)
    assert nightly.select(day, 5)["cases"] == nightly.select(day)["cases"][:5]
    with pytest.raises(ValueError, match="at least 1"):
        nightly.select(day, 0)


def run_dir(tmp_path: Path) -> Path:
    results = tmp_path / "nightly"
    results.mkdir()
    for name in nightly.KEPT:
        shutil.copyfile(RECORDING / name, results / name)
    (results / "golden.json").write_text(json.dumps(nightly.select(dt.date(2026, 12, 1), 5)), encoding="utf-8")
    result = {"dispute_id": "DSP-000002", "outcome": {}, "usage": {"models": ["claude-haiku-5-5", "claude-opus-5-5"]}}
    (results / "DSP-000002.json").write_text(json.dumps(result), encoding="utf-8")
    return results


def test_the_job_summary_names_the_slice_the_gate_and_the_numbers(tmp_path: Path) -> None:
    text = nightly.summary_markdown(run_dir(tmp_path), "fail")
    assert text.startswith("## Nightly live evaluation, 2026-12-01: FAILED")
    assert "models `claude-haiku-5-5`, `claude-opus-5-5`" in text
    assert "| Decision accuracy | 160/160 |" in text


def test_keep_writes_the_run_files_and_what_was_evaluated(tmp_path: Path) -> None:
    results = run_dir(tmp_path)
    out = tmp_path / "kept" / "2026-12-01-run1"
    nightly.keep(results, out, "abc1234", "pass")
    assert sorted(p.name for p in out.iterdir()) == ["grades.json", "nightly.json", "run.json", "summary.json"]
    kept = json.loads((out / "nightly.json").read_text(encoding="utf-8"))
    assert kept["commit"] == "abc1234" and kept["gate"] == "pass" and kept["limit"] == 5
    assert kept["models"] == ["claude-haiku-5-5", "claude-opus-5-5"]
    assert kept["cases"] == [c["dispute_id"] for c in nightly.select(dt.date(2026, 12, 1), 5)["cases"]]
