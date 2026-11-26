"""The nightly live evaluation (ADR 0009, section 4): the day's slice of golden set v2, run live under a spend cap.

    uv run python -m evaltools.nightly select --out runs/nightly/golden.json            # today's slice (UTC)
    uv run python -m evaltools.nightly select --out runs/nightly/golden.json --limit 5  # its first 5 cases
    uv run python -m evaltools.nightly summary --results runs/nightly --gate pass       # Markdown for the job summary
    uv run python -m evaltools.nightly keep --results runs/nightly --commit <sha> --gate pass --out nightly/<date>

``.github/workflows/nightly-eval.yml`` runs these around the eval runner and the live grade. ``select`` writes a
golden file of the slice's cases, in the shape the eval runner and the graders read. ``keep`` writes what is
committed to the branch ``eval-nightly``: the run's ``run.json``, ``summary.json`` and ``grades.json``, and
``nightly.json`` with the date, the slice, the commit evaluated, the models that answered and the gate's verdict.
"""

import argparse
import datetime as dt
import json
import shutil
import sys
from pathlib import Path
from typing import Any

from . import golden

ROOT = golden.ROOT
GOLDEN = golden.path_for(golden.V2)
SLICES = golden.slices_path_for(golden.V2)
KEPT = ("run.json", "summary.json", "grades.json")


def _json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8", newline="\n")


def select(day: dt.date, limit: int | None = None, golden_file: Path = GOLDEN, slices_file: Path = SLICES) -> dict:
    """The golden subset for ``day``: its slice (``golden.slice_of``), cut to the first ``limit`` cases by id."""
    full, slices = _json(golden_file), _json(slices_file)
    if slices["golden_set"] != full["golden_set"]:
        raise ValueError(f"slices of {slices['golden_set']}, golden set {full['golden_set']}")
    index = golden.slice_of(day, len(slices["slices"]))
    ids = sorted(slices["slices"][index])
    if limit is not None:
        if limit < 1:
            raise ValueError("limit must be at least 1")
        ids = ids[:limit]
    by_id = {c["dispute_id"]: c for c in full["cases"]}
    return {
        "golden_set": f"{full['golden_set']}-slice-{index}",
        "description": f"Nightly slice {index} of {full['golden_set']} for {day.isoformat()}"
        + (f", first {limit} cases" if limit is not None else "")
        + ".",
        "nightly": {"date": day.isoformat(), "slice": index, "slices": len(slices["slices"]), "limit": limit},
        "dataset": full["dataset"],
        "cases": [by_id[i] for i in ids],
    }


def _models(results: Path) -> list[str]:
    models: set[str] = set()
    for f in results.glob("DSP-*.json"):
        models.update(_json(f).get("usage", {}).get("models", []))
    return sorted(models)


def summary_markdown(results: Path, gate: str) -> str:
    s, run, selected = _json(results / "summary.json"), _json(results / "run.json"), _json(results / "golden.json")
    o, n = s["overall"], selected["nightly"]
    cost = s["cost_micro_usd"]
    capped = " (stopped by the spend cap)" if run["cases_run"] < run["cases"] else ""
    lines = [
        f"## Nightly live evaluation, {n['date']}: {'pass' if gate == 'pass' else 'FAILED'}",
        "",
        f"Slice {n['slice']} of {n['slices']} of `{GOLDEN.stem}`: {run['cases_run']} of {run['cases']} cases "
        f"run{capped}, adapter `{run['adapter']}`, models {', '.join(f'`{m}`' for m in _models(results)) or 'none'}.",
        "",
        "| Metric | Result |",
        "|---|---|",
        f"| Decision accuracy | {o['decision_accuracy']['n']}/{o['decision_accuracy']['of']} |",
        f"| Unsafe proposals (passed the guard) | {o['unsafe']['total']['n']} ({o['unsafe']['passed_guard']}) |",
        f"| Obeyed injections | {o['obeyed_injection']['n']}/{o['obeyed_injection']['of']} |",
        f"| Escalation precision, recall | {o['escalation']['precision']['n']}/{o['escalation']['precision']['of']}, "
        f"{o['escalation']['recall']['n']}/{o['escalation']['recall']['of']} |",
        f"| Unanswered | {o['escalation']['unanswered']} |",
        f"| Cost | total ${cost['total'] / 1e6:.4f}, median ${cost['median'] / 1e6:.4f} (cap "
        f"${run['max_cost_micro_usd'] / 1e6:.2f}) |",
    ]
    if s.get("wall_clock_ms"):
        lines.append(
            f"| Wall-clock per case | median {s['wall_clock_ms']['case']['median'] / 1000:.1f} s, p99 "
            f"{s['wall_clock_ms']['case']['p99'] / 1000:.1f} s |"
        )
    return "\n".join(lines) + "\n"


def keep(results: Path, out: Path, commit: str, gate: str) -> None:
    selected = _json(results / "golden.json")
    out.mkdir(parents=True, exist_ok=True)
    for name in KEPT:
        shutil.copyfile(results / name, out / name)
    run = _json(results / "run.json")
    _write(
        out / "nightly.json",
        selected["nightly"]
        | {
            "golden_set": golden.V2.name,
            "cases": [c["dispute_id"] for c in selected["cases"]],
            "cases_run": run["cases_run"],
            "commit": commit,
            "models": _models(results),
            "gate": gate,
        },
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("select", help="write the golden file of the day's slice")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--date", type=dt.date.fromisoformat, help="default: today (UTC)")
    p.add_argument("--limit", type=int, help="only the slice's first N cases")
    p = commands.add_parser("summary", help="print the job summary (Markdown)")
    p.add_argument("--results", type=Path, required=True)
    p.add_argument("--gate", choices=("pass", "fail"), required=True)
    p = commands.add_parser("keep", help="write the files committed to eval-nightly")
    p.add_argument("--results", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--gate", choices=("pass", "fail"), required=True)
    args = parser.parse_args(argv)

    if args.command == "select":
        day = args.date or dt.datetime.now(dt.UTC).date()
        selected = select(day, args.limit)
        _write(args.out, selected)
        n = selected["nightly"]
        print(f"{n['date']}: slice {n['slice']} of {n['slices']}, {len(selected['cases'])} cases -> {args.out}")
    elif args.command == "summary":
        print(summary_markdown(args.results, args.gate), end="")
    else:
        keep(args.results, args.out, args.commit, args.gate)
        print(f"kept {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
