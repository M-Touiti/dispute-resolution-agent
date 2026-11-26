"""Select the golden sets from the generated ground truth, deterministically, and cut golden set v2 into slices.

Each case is ranked within its scenario by SHA-256 of ``<salt>:<dispute id>`` and the first ``per_scenario`` are
kept, so the selection does not depend on file order and a larger ``per_scenario`` keeps every earlier pick.
Adversarial variants (generator v4) never take a scenario's place: they are ranked within their kind and the first
``adversarial_per_kind`` are kept. Each output records the dataset it came from (seed, generator version, file
hashes); a test regenerates every file and compares bytes, so a generator change that moves a golden set fails CI
until the set is re-selected and reviewed.

    uv run python -m evaltools.golden            # rewrite golden/*.json from ../data/generated
"""

import argparse
import csv
import datetime as dt
import hashlib
import json
from collections import defaultdict
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
DEFAULT_DATA = ROOT.parent / "data" / "generated"

CATEGORIES = (
    "UNAUTHORISED_TRANSACTION",
    "DUPLICATE_CHARGE",
    "GOODS_NOT_RECEIVED",
    "CANCELLED_RECURRING",
    "AMOUNT_MISMATCH",
)
ADVERSARIAL_KINDS = ("INJECTION", "OVERCLAIM")


@dataclass(frozen=True)
class Selection:
    name: str
    description: str
    categories: tuple[str, ...]
    per_scenario: int
    salt: str
    adversarial_per_kind: int = 0
    version: int = 1
    """Version 2 adds the adversarial kind, the credit ceiling and the injected goal to every case."""


V1 = Selection(
    name="unauthorised-v1",
    description=(
        "M1 golden set: 6 cases per unauthorised-transaction scenario (approve, escalate above the credit limit, "
        "chip and PIN, 3-D Secure, out of time)."
    ),
    categories=("UNAUTHORISED_TRANSACTION",),
    per_scenario=6,
    salt="golden-v1",
)

V2 = Selection(
    name="all-categories-v2",
    description=(
        "M3 golden set (ADR 0009): 8 cases for each of the 17 scenarios of the five reason categories, keeping every "
        "case of unauthorised-v1, and 12 adversarial variants of each kind (injection, overclaim), whose correct "
        "decision is their scenario's."
    ),
    categories=CATEGORIES,
    per_scenario=8,
    salt="golden-v1",
    adversarial_per_kind=12,
    version=2,
)

SELECTIONS = (V1, V2)

# The nightly rotation (ADR 0009): slices of golden set v2.
SLICE_SIZE = 30
SLICE_SALT = "slices-v1"
EPOCH = dt.date(1970, 1, 1)


def _split(value: str) -> list[str]:
    return value.split(";") if value else []


def _rank(salt: str, dispute_id: str) -> str:
    return hashlib.sha256(f"{salt}:{dispute_id}".encode()).hexdigest()


def _kind(row: dict[str, str]) -> str:
    return row.get("adversarial") or "NONE"  # v3 ground truth has no such column


def _top(rows: list[dict[str, str]], salt: str, count: int, what: str) -> list[dict[str, str]]:
    ranked = sorted(rows, key=lambda r: _rank(salt, r["dispute_id"]))
    if len(ranked) < count:
        raise ValueError(f"{what}: {len(ranked)} cases, {count} needed")
    return ranked[:count]


def case_from_row(row: dict[str, str], version: int) -> dict[str, Any]:
    """One golden case from a ground-truth row; version 2 adds the adversarial kind, credit ceiling and goal."""
    case: dict[str, Any] = {
        "dispute_id": row["dispute_id"],
        "category": row["category"],
        "scenario": row["scenario"],
        "difficulty": row["difficulty"],
        "expected_decision": row["expected_decision"],
        "required_evidence_ids": _split(row["required_evidence_ids"]),
        "allowed_actions": _split(row["allowed_actions"]),
        "forbidden_actions": _split(row["forbidden_actions"]),
        "deciding_section": row["deciding_section"],
        "rationale": row["rationale"],
    }
    if version >= 2:
        case["adversarial"] = _kind(row)
        case["credit_ceiling_minor"] = int(row["credit_ceiling_minor"])
        case["injected_goal"] = (
            {
                "outcome": row["injected_outcome"],
                "action": row["injected_action"],
                "amount_minor": int(row["injected_amount_minor"]) if row["injected_amount_minor"] else None,
            }
            if row["injected_outcome"]
            else None
        )
    return case


def _manifest(data_dir: Path) -> dict[str, Any]:
    path = data_dir / "manifest.json"
    if not path.exists():
        raise FileNotFoundError(f"{path} not found: run `uv run python generate.py` in data/ first")
    return json.loads(path.read_text(encoding="utf-8"))


def _dataset(manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "seed": manifest["config"]["seed"],
        "as_of": manifest["config"]["as_of"],
        "generator_version": manifest["generator_version"],
        "ground_truth_sha256": manifest["sha256"]["ground_truth.csv"],
        "seed_sql_sha256": manifest["sha256"]["seed.sql"],
    }


def select(data_dir: Path, selection: Selection) -> dict[str, Any]:
    manifest = _manifest(data_dir)
    with (data_dir / "ground_truth.csv").open(encoding="utf-8", newline="") as f:
        rows = [r for r in csv.DictReader(f) if r["category"] in selection.categories]

    by_scenario: dict[str, list[dict[str, str]]] = defaultdict(list)
    by_kind: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        if _kind(row) == "NONE":
            by_scenario[row["scenario"]].append(row)
        else:
            by_kind[_kind(row)].append(row)

    picked: list[dict[str, str]] = []
    for scenario in sorted(by_scenario):
        picked.extend(_top(by_scenario[scenario], selection.salt, selection.per_scenario, scenario))
    if selection.adversarial_per_kind:
        for kind in ADVERSARIAL_KINDS:
            picked.extend(_top(by_kind[kind], selection.salt, selection.adversarial_per_kind, kind))

    criteria: dict[str, Any] = {
        "categories": list(selection.categories),
        "per_scenario": selection.per_scenario,
        "salt": selection.salt,
    }
    if selection.adversarial_per_kind:
        criteria["adversarial_per_kind"] = selection.adversarial_per_kind
    return {
        "golden_set": selection.name,
        "description": selection.description,
        "selection": criteria,
        "dataset": _dataset(manifest),
        "cases": [case_from_row(r, selection.version) for r in sorted(picked, key=lambda r: r["dispute_id"])],
    }


def slices(golden: dict[str, Any], size: int = SLICE_SIZE) -> dict[str, Any]:
    """Cut a golden set into overlapping slices of ``size`` cases for the nightly rotation (ADR 0009).

    The cases are laid out on a circle so that every stratum is spread evenly around it: an adversarial case in the
    stratum of its kind, any other case in the stratum of its category, ranked within the stratum by SHA-256 of
    ``slices-v1:<dispute id>``. Slice ``k`` is the ``size`` consecutive cases from position ``k * n // count``, with
    ``count`` the smallest number of slices that covers the circle. So every case is in at least one slice, and every
    slice holds each stratum in proportion: each adversarial kind and each category.
    """
    strata: dict[str, list[str]] = defaultdict(list)
    for case in golden["cases"]:
        kind = case["adversarial"]
        strata[kind if kind != "NONE" else case["category"]].append(case["dispute_id"])
    positioned = []
    for stratum, ids in sorted(strata.items()):
        ranked = sorted(ids, key=lambda i: _rank(SLICE_SALT, i))
        positioned.extend((Fraction(2 * i + 1, 2 * len(ranked)), stratum, d) for i, d in enumerate(ranked))
    circle = [d for _, _, d in sorted(positioned)]
    n = len(circle)
    if n < size:
        raise ValueError(f"{golden['golden_set']}: {n} cases, fewer than a slice of {size}")
    count = -(-n // size)
    return {
        "golden_set": golden["golden_set"],
        "description": (
            f"Slices of {size} cases for the nightly live evaluation (ADR 0009): the slice of a day is the number of "
            f"days since 1970-01-01 (UTC) modulo {count}. Every case is in at least one slice; each slice holds the "
            "categories and the adversarial kinds in proportion."
        ),
        "selection": {"size": size, "salt": SLICE_SALT},
        "dataset": golden["dataset"],
        "slices": [sorted(circle[(k * n // count + j) % n] for j in range(size)) for k in range(count)],
    }


def slice_of(day: dt.date, count: int) -> int:
    """The index of the slice the nightly run evaluates on ``day`` (UTC): no state to keep between runs."""
    return (day - EPOCH).days % count


def render(document: dict[str, Any]) -> str:
    return json.dumps(document, indent=2, ensure_ascii=True) + "\n"


def path_for(selection: Selection) -> Path:
    return ROOT / "golden" / f"{selection.name}.json"


def slices_path_for(selection: Selection) -> Path:
    return ROOT / "golden" / f"{selection.name}-slices.json"


def outputs(data_dir: Path) -> dict[Path, str]:
    """Every committed golden file and the text it must have."""
    out = {}
    for selection in SELECTIONS:
        golden = select(data_dir, selection)
        out[path_for(selection)] = render(golden)
        if selection.version >= 2:
            out[slices_path_for(selection)] = render(slices(golden))
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="generated dataset directory")
    args = parser.parse_args()
    for path, text in outputs(args.data).items():
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
