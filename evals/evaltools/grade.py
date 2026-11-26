"""Grade an evaluation run and apply the regression rule.

    uv run python -m evaltools.grade                                   # runs/latest against the committed baseline
    uv run python -m evaltools.grade --results runs/latest --write-baseline   # after an intended change
    uv run python -m evaltools.grade --results runs/recording --golden golden/all-categories-v2.json \\
        --write-thresholds                                             # the live thresholds, from the full recording

A replayed run (the default when ``run.json`` names the replay adapter) is gated against the baseline
(``graders.gate``); a live run against ``baseline/live-thresholds.json`` (``graders.live_gate``), on the cases it ran.
Writes ``summary.json`` and ``grades.json`` into the results directory, prints a table, and exits 1 when the run
fails its gate.
"""

import argparse
import json
import sys
from pathlib import Path

from . import graders

ROOT = graders.ROOT


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--golden", type=Path, default=ROOT / "golden" / "all-categories-v2.json")
    parser.add_argument("--results", type=Path, default=ROOT / "runs" / "latest")
    parser.add_argument("--data", type=Path, default=graders.REPOSITORY / "data" / "generated")
    parser.add_argument("--corpus", type=Path, default=graders.REPOSITORY / "data" / "policy-corpus")
    parser.add_argument("--baseline", type=Path, default=ROOT / "baseline" / "all-categories-v2.json")
    parser.add_argument("--thresholds", type=Path, default=ROOT / "baseline" / "live-thresholds.json")
    parser.add_argument(
        "--mode", choices=("replay", "live"), help="which gate applies (default: from the adapter in run.json)"
    )
    parser.add_argument("--write-baseline", action="store_true", help="store this run's summary as the baseline")
    parser.add_argument(
        "--write-thresholds", action="store_true", help="store this run as the live thresholds (the full recording)"
    )
    args = parser.parse_args(argv)

    golden = json.loads(args.golden.read_text(encoding="utf-8"))
    results = graders.load_results(args.results)
    evidence = graders.case_evidence(args.data)
    sections = graders.corpus_sections(args.corpus)
    run_file = args.results / "run.json"
    run = json.loads(run_file.read_text(encoding="utf-8")) if run_file.exists() else {}
    adapter = run.get("adapter", "replay")
    live = (args.mode or ("replay" if adapter not in graders.LIVE_ADAPTERS else "live")) == "live"

    cases = graders.complete_cases(golden["cases"], args.data)
    # A live run is graded on the cases it ran (it may have stopped at its spend cap); a replayed run on all of them,
    # so a missing result fails.
    graded = [c for c in cases if c["dispute_id"] in results] if live else cases
    grades = [
        graders.grade_case(case, results.get(case["dispute_id"]), evidence.get(case["dispute_id"], set()), sections)
        for case in graded
    ]
    summary = graders.summarise(
        grades, {g.dispute_id: results[g.dispute_id] for g in grades if g.dispute_id in results}, live=live
    )
    summary["golden_set"] = golden["golden_set"]
    summary["mode"] = "live" if live else "replay"
    summary["capped"] = live and len(graded) < len(cases)

    _write(args.results / "summary.json", summary)
    _write(args.results / "grades.json", [g.__dict__ | {"passed": g.passed} for g in grades])
    _print(summary, grades)

    if args.write_baseline:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        _write(args.baseline, summary)
        print(f"\nbaseline written: {args.baseline}")
        return 0
    if args.write_thresholds:
        if any(c["dispute_id"] not in results for c in cases):
            print("\nthresholds come from a full run: this one did not run every case")
            return 1
        args.thresholds.parent.mkdir(parents=True, exist_ok=True)
        recorded = f"{args.results.name} ({adapter}, {len(grades)} cases)"
        _write(args.thresholds, graders.thresholds_from(grades, golden["golden_set"], recorded))
        print(f"\nlive thresholds written: {args.thresholds}")
        return 0

    if live:
        thresholds = json.loads(args.thresholds.read_text(encoding="utf-8")) if args.thresholds.exists() else None
        if thresholds is None:
            print(f"\nno thresholds at {args.thresholds}: only the 'no unanswered case but an outage' rule applies")
        problems = graders.live_gate(grades, thresholds)
    else:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.exists() else None
        if baseline is None:
            print(f"\nno baseline at {args.baseline}: only the 'every case answered' rule applies")
        problems = graders.gate(summary, baseline)
    if problems:
        print(("\nLIVE RUN FAILED:\n  " if live else "\nREGRESSION:\n  ") + "\n  ".join(problems))
        return 1
    print("\ngate: pass")
    return 0


def _write(path: Path, value: object) -> None:
    # LF on every platform: the baseline and the thresholds are committed.
    text = json.dumps(value, indent=2, ensure_ascii=True, default=list) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def _count(r: dict) -> str:
    return f"{r['n']}/{r['of']}"


def _print(summary: dict, grades: list[graders.CaseGrade]) -> None:
    overall = summary["overall"]
    capped = ", capped" if summary["capped"] else ""
    print(
        f"{summary['golden_set']} ({summary['mode']}{capped}): {summary['cases']} cases, decision accuracy "
        f"{_count(overall['decision_accuracy'])}, unsafe proposals {_count(overall['unsafe']['total'])} "
        f"(passed the guard {overall['unsafe']['passed_guard']}, stopped {overall['unsafe']['stopped_by_guard']})"
    )
    for check, r in summary["checks"].items():
        rate = "-" if r["rate"] is None else f"{r['rate']:.2f}"
        print(f"  {check:24} {rate:>5}  pass {r['pass']:>3}  fail {r['fail']:>3}  n/a {r['na']:>3}")
    for key in ("per_category", "per_adversarial"):
        for name, m in summary[key].items():
            print(
                f"  {name:34} accuracy {_count(m['decision_accuracy']):>7}  unsafe {_count(m['unsafe']['total']):>7}"
                f"  over-generous {m['over_generous_credit']['n']}  obeyed injection "
                f"{_count(m['obeyed_injection'])}"
            )
    escalation = overall["escalation"]
    print(
        f"  escalation: precision {_count(escalation['precision'])}, recall {_count(escalation['recall'])}, "
        f"unanswered {escalation['unanswered']}"
    )
    cost = summary["cost_micro_usd"]
    print(
        f"  cost: total ${cost['total'] / 1e6:.4f}, median ${cost['median'] / 1e6:.4f}, p99 ${cost['p99'] / 1e6:.4f}; "
        f"cache hit ratio {summary['cache_hit_ratio']} (M1's definition {summary['cache_read_ratio']})"
    )
    wall = summary["wall_clock_ms"]
    if wall:
        print(
            f"  wall-clock: case median {wall['case']['median']} ms, p99 {wall['case']['p99']} ms; "
            f"model call median {wall['model_call']['median']} ms, p99 {wall['model_call']['p99']} ms"
        )
    failing = [g for g in grades if not g.passed or g.unsafe != "none"]
    for g in failing[:10]:
        print(
            f"  FAIL {g.dispute_id} ({g.scenario}): expected {g.expected}, proposed {g.proposed}; " + "; ".join(g.notes)
        )
    if len(failing) > 10:
        print(f"  ... and {len(failing) - 10} more in grades.json")


if __name__ == "__main__":
    sys.exit(main())
