"""Grade an evaluation run and apply the regression rule.

    uv run python -m evaltools.grade                                   # runs/latest against the committed baseline
    uv run python -m evaltools.grade --results runs/latest --write-baseline   # after an intended change

Writes ``summary.json`` and ``grades.json`` into the results directory, prints a table, and exits 1 when the run
must not merge (see ``graders.gate``).
"""

import argparse
import json
import sys
from pathlib import Path

from . import graders

ROOT = graders.ROOT


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--golden", type=Path, default=ROOT / "golden" / "unauthorised-v1.json")
    parser.add_argument("--results", type=Path, default=ROOT / "runs" / "latest")
    parser.add_argument("--data", type=Path, default=graders.REPOSITORY / "data" / "generated")
    parser.add_argument("--corpus", type=Path, default=graders.REPOSITORY / "data" / "policy-corpus")
    parser.add_argument("--baseline", type=Path, default=ROOT / "baseline" / "unauthorised-v1.json")
    parser.add_argument("--write-baseline", action="store_true", help="store this run's summary as the baseline")
    args = parser.parse_args(argv)

    golden = json.loads(args.golden.read_text(encoding="utf-8"))
    results = graders.load_results(args.results)
    evidence = graders.case_evidence(args.data)
    sections = graders.corpus_sections(args.corpus)

    grades = [
        graders.grade_case(case, results.get(case["dispute_id"]), evidence.get(case["dispute_id"], set()), sections)
        for case in golden["cases"]
    ]
    summary = graders.summarise(
        grades, {g.dispute_id: results[g.dispute_id] for g in grades if g.dispute_id in results}
    )
    summary["golden_set"] = golden["golden_set"]

    _write(args.results / "summary.json", summary)
    _write(args.results / "grades.json", [g.__dict__ | {"passed": g.passed} for g in grades])
    _print(summary, grades)

    if args.write_baseline:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        _write(args.baseline, summary)
        print(f"\nbaseline written: {args.baseline}")
        return 0

    baseline = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.exists() else None
    problems = graders.gate(summary, baseline)
    if baseline is None:
        print(f"\nno baseline at {args.baseline}: only the 'every case answered' rule applies")
    if problems:
        print("\nREGRESSION:\n  " + "\n  ".join(problems))
        return 1
    print("\ngate: pass")
    return 0


def _write(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True, default=list) + "\n", encoding="utf-8")


def _print(summary: dict, grades: list[graders.CaseGrade]) -> None:
    print(
        f"{summary['golden_set']}: {summary['cases']} cases, decision accuracy {summary['decision_accuracy']}, "
        f"unsafe proposals {summary['unsafe_proposals']}"
    )
    for check, r in summary["checks"].items():
        rate = "-" if r["rate"] is None else f"{r['rate']:.2f}"
        print(f"  {check:24} {rate:>5}  pass {r['pass']:>3}  fail {r['fail']:>3}  n/a {r['na']:>3}")
    for scenario, s in summary["per_scenario"].items():
        print(f"  {scenario:34} accuracy {s['decision_accuracy']:.2f} ({s['cases']} cases)")
    cost = summary["cost_micro_usd"]
    print(
        f"  cost: total ${cost['total'] / 1e6:.4f}, median ${cost['median'] / 1e6:.4f}, p90 ${cost['p90'] / 1e6:.4f}; "
        f"cache read ratio {summary['cache_read_ratio']}"
    )
    failing = [g for g in grades if not g.passed]
    for g in failing[:10]:
        print(
            f"  FAIL {g.dispute_id} ({g.scenario}): expected {g.expected}, proposed {g.proposed}; " + "; ".join(g.notes)
        )
    if len(failing) > 10:
        print(f"  ... and {len(failing) - 10} more in grades.json")


if __name__ == "__main__":
    sys.exit(main())
