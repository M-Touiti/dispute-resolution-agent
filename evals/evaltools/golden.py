"""Select a golden set from the generated ground truth, deterministically.

Each case is ranked within its scenario by SHA-256 of ``<salt>:<dispute id>`` and the first ``per_scenario`` are
kept, so the selection does not depend on file order and a larger ``per_scenario`` keeps every earlier pick. The
output records the dataset it came from (seed, generator version, file hashes); a test regenerates it and compares
bytes, so a generator change that moves the golden set fails CI until the set is re-selected and reviewed.

    uv run python -m evaltools.golden            # rewrite golden/unauthorised-v1.json from ../data/generated
"""

import argparse
import csv
import hashlib
import json
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
DEFAULT_DATA = ROOT.parent / "data" / "generated"


@dataclass(frozen=True)
class Selection:
    name: str
    description: str
    categories: tuple[str, ...]
    per_scenario: int
    salt: str


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


def _split(value: str) -> list[str]:
    return value.split(";") if value else []


def _rank(salt: str, dispute_id: str) -> str:
    return hashlib.sha256(f"{salt}:{dispute_id}".encode()).hexdigest()


def select(data_dir: Path, selection: Selection) -> dict[str, Any]:
    manifest_path = data_dir / "manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError(f"{manifest_path} not found: run `uv run python generate.py` in data/ first")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    with (data_dir / "ground_truth.csv").open(encoding="utf-8", newline="") as f:
        rows = [r for r in csv.DictReader(f) if r["category"] in selection.categories]

    by_scenario: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        by_scenario[row["scenario"]].append(row)

    picked: list[dict[str, str]] = []
    for scenario in sorted(by_scenario):
        candidates = sorted(by_scenario[scenario], key=lambda r: _rank(selection.salt, r["dispute_id"]))
        if len(candidates) < selection.per_scenario:
            raise ValueError(f"{scenario}: {len(candidates)} cases, {selection.per_scenario} needed")
        picked.extend(candidates[: selection.per_scenario])

    cases = [
        {
            "dispute_id": r["dispute_id"],
            "category": r["category"],
            "scenario": r["scenario"],
            "difficulty": r["difficulty"],
            "expected_decision": r["expected_decision"],
            "required_evidence_ids": _split(r["required_evidence_ids"]),
            "allowed_actions": _split(r["allowed_actions"]),
            "forbidden_actions": _split(r["forbidden_actions"]),
            "deciding_section": r["deciding_section"],
            "rationale": r["rationale"],
        }
        for r in sorted(picked, key=lambda r: r["dispute_id"])
    ]
    return {
        "golden_set": selection.name,
        "description": selection.description,
        "selection": {
            "categories": list(selection.categories),
            "per_scenario": selection.per_scenario,
            "salt": selection.salt,
        },
        "dataset": {
            "seed": manifest["config"]["seed"],
            "as_of": manifest["config"]["as_of"],
            "generator_version": manifest["generator_version"],
            "ground_truth_sha256": manifest["sha256"]["ground_truth.csv"],
            "seed_sql_sha256": manifest["sha256"]["seed.sql"],
        },
        "cases": cases,
    }


def render(golden: dict[str, Any]) -> str:
    return json.dumps(golden, indent=2, ensure_ascii=True) + "\n"


def path_for(selection: Selection) -> Path:
    return ROOT / "golden" / f"{selection.name}.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="generated dataset directory")
    args = parser.parse_args()
    out = path_for(V1)
    out.write_text(render(select(args.data, V1)), encoding="utf-8", newline="\n")
    print(f"wrote {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
