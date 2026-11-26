"""The model-graded rubric (ADR 0009, section 3): a grader model scores the prose of a decided case, and a person's
blind labels measure how far its scores can be trusted.

    uv run python -m evaltools.rubric grade --results runs/recording --golden golden/all-categories-v2.json
        # a dry run: the cases, their size and the estimated cost; nothing is sent
    uv run --group rubric python -m evaltools.rubric grade ... --live      # costs money: needs a go-ahead
    uv run python -m evaltools.rubric summary --answers rubric/answers/recording
    uv run python -m evaltools.rubric pick --results runs/recording --golden golden/all-categories-v2.json
    uv run python -m evaltools.rubric agree --labels rubric/calibration/labels.json --answers rubric/answers/recording

What the grader sees, per case: the written rubric (``rubric/<version>.md``), the dispute as the agent received it,
every tool call the agent made with its result (from the recorded fixtures: the case's last investigation request
holds the whole conversation), the decision, and the text of the policy sections it cites. Never the expected
decision. Every answer is stored (``rubric/answers/<run>/<dispute id>.json``) so the report rebuilds with no key, and an
answer whose input has not changed is never asked for again.

The Python SDK is an optional dependency group (``rubric``), imported only by a live run: the graders, the summary and
the calibration stay standard library.
"""

import argparse
import hashlib
import json
import os
import re
import sys
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import graders

ROOT = graders.ROOT
RUBRIC_DIR = ROOT / "rubric"
RUBRIC_VERSION = "rubric-v1"
CRITERIA = ("faithful", "evidence_check_complete", "merchant_request_clear")
VERDICTS = ("pass", "fail", "na")
MERCHANT_REQUEST = "REQUEST_MERCHANT_EVIDENCE"

# The grader's settings: defaults here, overridden on the command line; the report records what was used.
DEFAULT_MODEL = "claude-opus-5-5"
DEFAULT_EFFORT = "medium"
MAX_TOKENS = 16000

# Calibration (ADR 0009): 6 cases per category, ranked by SHA-256 of "rubric-v1:<dispute id>".
CALIBRATION_SALT = "rubric-v1"
PER_CATEGORY = 6
# So that criterion 3 has cases to agree on; see ``pick``.
MIN_MERCHANT_REQUESTS = 6
# A criterion's score is published as a metric only above both (ADR 0009).
MIN_AGREEMENT = 0.8
MIN_KAPPA = 0.6

_VERDICT = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "verdict": {"type": "string", "enum": list(VERDICTS)},
        "reason": {"type": "string", "description": "One sentence that quotes the words judged."},
    },
    "required": ["verdict", "reason"],
}
SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {c: _VERDICT for c in CRITERIA},
    "required": list(CRITERIA),
}

_INVESTIGATE = re.compile(r"^Investigate dispute (DSP-\d+)\.")
_SECTION = re.compile(r"^Section `([a-z0-9]+(?:-[a-z0-9]+)*)`;", re.MULTILINE)


# Inputs -----------------------------------------------------------------------------------------------------------


def rubric_text(version: str = RUBRIC_VERSION) -> str:
    return (RUBRIC_DIR / f"{version}.md").read_text(encoding="utf-8")


def policy_sections(corpus_dir: Path) -> dict[str, str]:
    """Each section's full text, from its ``## `` heading to the next heading."""
    out = {}
    for f in sorted(corpus_dir.glob("*.md")):
        if f.name == "README.md":
            continue
        for block in re.split(r"^(?=#{1,2} )", f.read_text(encoding="utf-8"), flags=re.MULTILINE):
            found = _SECTION.search(block)
            if block.startswith("## ") and found:
                out[found.group(1)] = block.strip()
    return out


def last_requests(fixtures_dir: Path) -> dict[str, dict[str, Any]]:
    """Per dispute, the last investigation request recorded: the one with the longest conversation, which holds every
    tool call and result of the case."""
    out: dict[str, dict[str, Any]] = {}
    for f in sorted(fixtures_dir.glob("*.json")):
        request = json.loads(f.read_text(encoding="utf-8"))["request"]
        found = _INVESTIGATE.match(_text(request["messages"][0]))
        if found and len(request["messages"]) > len(out.get(found.group(1), {"messages": ()})["messages"]):
            out[found.group(1)] = request
    return out


def _text(message: dict[str, Any]) -> str:
    content = message["content"]
    if isinstance(content, str):
        return content
    return "\n".join(b["text"] for b in content if b.get("type") == "text")


def _blocks(message: dict[str, Any]) -> list[dict[str, Any]]:
    return message["content"] if isinstance(message["content"], list) else []


def case_packet(
    dispute_id: str, category: str, result: dict[str, Any], request: dict[str, Any], sections: dict[str, str]
) -> dict[str, Any]:
    """What the grader judges a case on. The expected decision is not in it, by construction: it is built from the
    agent's own request and decision and the policy corpus only."""
    decision = result["outcome"]["decision"]
    if decision is None:
        raise ValueError(f"{dispute_id} has no decision to grade")
    calls = {
        b["id"]: b
        for m in request["messages"]
        if m["role"] == "assistant"
        for b in _blocks(m)
        if b.get("type") == "tool_use"
    }
    tool_results = []
    for m in request["messages"]:
        if m["role"] != "user":
            continue
        for b in _blocks(m):
            call = calls.get(b.get("tool_use_id", ""))
            # Skills are the agent's instructions, not records about the case.
            if b.get("type") != "tool_result" or call is None or call["name"] == "load_skill":
                continue
            content = b["content"] if isinstance(b["content"], str) else _text({"content": b["content"]})
            tool_results.append(
                {"tool": call["name"], "input": call["input"], "is_error": b.get("is_error", False), "content": content}
            )
    return {
        "dispute_id": dispute_id,
        "category": category,
        "dispute": _text(request["messages"][0]),
        "tool_results": tool_results,
        "policy_sections": {
            s: sections.get(s, "(no such section in the policy)") for s in decision["cited_policy_sections"]
        },
        "decision": decision,
    }


def render(packet: dict[str, Any]) -> str:
    """The user message: the case, every part in its own tags, as data."""

    def call(r: dict[str, Any]) -> str:
        error = ' is_error="true"' if r["is_error"] else ""
        arguments = _attr(json.dumps(r["input"], sort_keys=True))
        return f'<tool_result tool="{r["tool"]}" input="{arguments}"{error}>\n{r["content"]}\n</tool_result>'

    calls = "\n".join(call(r) for r in packet["tool_results"])
    sections = "\n".join(
        f'<section id="{s}">\n{text}\n</section>' for s, text in sorted(packet["policy_sections"].items())
    )
    decision = json.dumps(packet["decision"], indent=2, sort_keys=True, ensure_ascii=False)
    return (
        f"Grade the decision for dispute {packet['dispute_id']} (category {packet['category']}).\n\n"
        f"<case>\n<dispute>\n{packet['dispute']}\n</dispute>\n"
        f"<tool_results>\n{calls}\n</tool_results>\n"
        f"<policy_sections>\n{sections}\n</policy_sections>\n"
        f"<decision>\n{decision}\n</decision>\n</case>"
    )


def _attr(value: str) -> str:
    return value.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;")


def input_sha256(system: str, user: str, model: str, effort: str) -> str:
    return hashlib.sha256(json.dumps([system, user, model, effort]).encode()).hexdigest()


# The grader -------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class RawAnswer:
    text: str | None
    stop_reason: str
    usage: dict[str, int]
    model: str
    response_id: str


Grader = Callable[[str, str], RawAnswer]


def request_params(system: str, user: str, model: str, effort: str) -> dict[str, Any]:
    """The Messages API request: non-streaming, adaptive thinking (the model's default), effort set explicitly, the
    stable rubric cached, the answer constrained by structured output."""
    return {
        "model": model,
        "max_tokens": MAX_TOKENS,
        "system": [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
        "messages": [{"role": "user", "content": user}],
        "output_config": {"effort": effort, "format": {"type": "json_schema", "schema": SCHEMA}},
    }


class AnthropicGrader:
    """The live grader. No server-side fallback model: an answer from another model would not be the grader the
    calibration measured, so a refusal is stored as unscored instead."""

    def __init__(self, model: str, effort: str, api_key: str, http_client: Any = None) -> None:
        import anthropic  # the optional `rubric` group

        # The SDK retries 408, 409, 429, 5xx and connection errors; the grader adds no retry of its own.
        self._client = anthropic.Anthropic(api_key=api_key, max_retries=3, http_client=http_client)
        self.model = model
        self.effort = effort

    def __call__(self, system: str, user: str) -> RawAnswer:
        response = self._client.messages.create(**request_params(system, user, self.model, self.effort))
        text = "".join(b.text for b in response.content if b.type == "text") or None
        usage = {
            k: int(getattr(response.usage, k) or 0)
            for k in ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
        }
        return RawAnswer(text, response.stop_reason or "", usage, response.model, response.id)


def api_key() -> str:
    """The key from the environment, else from the repository's git-ignored ``.env``. Never printed."""
    key = os.environ.get("ANTHROPIC_API_KEY", "")
    env = graders.REPOSITORY / ".env"
    if not key and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            name, _, value = line.partition("=")
            if name.strip() == "ANTHROPIC_API_KEY":
                key = value.strip().strip('"').strip("'")
    if not key:
        raise SystemExit("ANTHROPIC_API_KEY is not set (environment or .env)")
    return key


def prices(model: str) -> dict[str, Any]:
    table = json.loads((RUBRIC_DIR / "prices.json").read_text(encoding="utf-8"))
    if model not in table["models"]:
        raise SystemExit(f"no price for {model} in rubric/prices.json")
    return {"version": table["version"], **table["models"][model]}


def cost_micro_usd(usage: dict[str, int], price: dict[str, Any]) -> int:
    """Dollars per million tokens times tokens is micro-dollars."""
    return round(
        usage.get("input_tokens", 0) * price["input"]
        + usage.get("output_tokens", 0) * price["output"]
        + usage.get("cache_read_input_tokens", 0) * price["cache_read"]
        + usage.get("cache_creation_input_tokens", 0) * price["cache_write"]
    )


def answer_record(packet: dict[str, Any], sha: str, raw: RawAnswer, effort: str, price: dict[str, Any]) -> dict:
    """The stored answer. ``scores`` is None when the grader gave none (a refusal, a cut-off answer, invalid JSON)."""
    scores, problem = None, None
    if raw.stop_reason != "end_turn" or raw.text is None:
        problem = f"stop_reason {raw.stop_reason}"
    else:
        try:
            scores = _scores(json.loads(raw.text))
        except (ValueError, KeyError, TypeError) as e:
            problem = f"invalid answer: {e}"
    if scores is not None and packet["decision"]["outcome"] != MERCHANT_REQUEST:
        # The criterion does not apply; the grader's verdict on it is kept in the reason, not counted.
        given = scores["merchant_request_clear"]
        if given["verdict"] != "na":
            scores["merchant_request_clear"] = {"verdict": "na", "reason": f"not applicable (grader: {given})"}
    return {
        "rubric_version": RUBRIC_VERSION,
        "dispute_id": packet["dispute_id"],
        "category": packet["category"],
        "outcome": packet["decision"]["outcome"],
        "input_sha256": sha,
        "model": raw.model,
        "effort": effort,
        "response_id": raw.response_id,
        "stop_reason": raw.stop_reason,
        "scores": scores,
        "problem": problem,
        "usage": raw.usage,
        "cost_micro_usd": cost_micro_usd(raw.usage, price),
        "price_table": price["version"],
    }


def _scores(answer: dict[str, Any]) -> dict[str, dict[str, str]]:
    out = {}
    for c in CRITERIA:
        verdict, reason = answer[c]["verdict"], answer[c]["reason"]
        if verdict not in VERDICTS or not isinstance(reason, str):
            raise ValueError(f"{c}: {verdict!r}")
        out[c] = {"verdict": verdict, "reason": reason}
    return out


@dataclass
class GradeRun:
    graded: int = 0
    reused: int = 0
    unscored: int = 0
    cost_micro_usd: int = 0
    capped: bool = False


def grade_run(
    packets: Iterable[dict[str, Any]],
    grader: Grader,
    out_dir: Path,
    model: str,
    effort: str,
    max_cost_micro_usd: int,
    system: str | None = None,
) -> GradeRun:
    """Grades each case once. An answer stored for the same input (rubric, case, model, effort) is reused, so a run
    that stopped at its cap continues where it stopped, and the cap is checked before each call."""
    system = system if system is not None else rubric_text()
    price = prices(model)
    out_dir.mkdir(parents=True, exist_ok=True)
    run = GradeRun()
    for packet in packets:
        user = render(packet)
        sha = input_sha256(system, user, model, effort)
        path = out_dir / f"{packet['dispute_id']}.json"
        if path.exists() and json.loads(path.read_text(encoding="utf-8"))["input_sha256"] == sha:
            run.reused += 1
            continue
        if run.cost_micro_usd >= max_cost_micro_usd:
            run.capped = True
            break
        record = answer_record(packet, sha, grader(system, user), effort, price)
        _write(path, record)
        run.graded += 1
        run.unscored += record["scores"] is None
        run.cost_micro_usd += record["cost_micro_usd"]
    return run


def estimate_micro_usd(packets: list[dict[str, Any]], model: str, system: str | None = None) -> int:
    """A rough cost before a live run: 4 characters a token, the rubric written to the cache once and read after,
    and about 1,500 output tokens a case (reasons plus adaptive thinking at medium effort)."""
    system = system if system is not None else rubric_text()
    price = prices(model)
    total = 0
    for i, p in enumerate(packets):
        usage = {
            "input_tokens": len(render(p)) // 4,
            "output_tokens": 1500,
            ("cache_creation_input_tokens" if i == 0 else "cache_read_input_tokens"): len(system) // 4,
        }
        total += cost_micro_usd(usage, price)
    return total


# Summary ----------------------------------------------------------------------------------------------------------


def load_answers(answers_dir: Path) -> list[dict[str, Any]]:
    return [
        json.loads(f.read_text(encoding="utf-8"))
        for f in sorted(answers_dir.glob("DSP-*.json"))
        if json.loads(f.read_text(encoding="utf-8")).get("rubric_version") == RUBRIC_VERSION
    ]


def summarise(answers: list[dict[str, Any]]) -> dict[str, Any]:
    """Per criterion, pass and fail over the cases it applies to, overall and per category. Uncalibrated until
    ``agree`` says otherwise."""

    def counts(group: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
        out = {}
        for c in CRITERIA:
            verdicts = [a["scores"][c]["verdict"] for a in group if a["scores"] is not None]
            passed, failed = verdicts.count("pass"), verdicts.count("fail")
            out[c] = {
                "pass": passed,
                "fail": failed,
                "na": verdicts.count("na"),
                "rate": round(passed / (passed + failed), 4) if passed + failed else None,
            }
        return out

    by_category: dict[str, list[dict[str, Any]]] = {}
    for a in answers:
        by_category.setdefault(a["category"], []).append(a)
    return {
        "rubric_version": RUBRIC_VERSION,
        "models": sorted({a["model"] for a in answers}),
        "cases": len(answers),
        "unscored": sorted(a["dispute_id"] for a in answers if a["scores"] is None),
        "cost_micro_usd": sum(a["cost_micro_usd"] for a in answers),
        "criteria": counts(answers),
        "per_category": {k: counts(v) for k, v in sorted(by_category.items())},
    }


# Calibration ------------------------------------------------------------------------------------------------------


def _rank(dispute_id: str) -> str:
    return hashlib.sha256(f"{CALIBRATION_SALT}:{dispute_id}".encode()).hexdigest()


def pick(
    decided: dict[str, tuple[str, str]],
    per_category: int = PER_CATEGORY,
    min_merchant_requests: int = MIN_MERCHANT_REQUESTS,
) -> list[str]:
    """The calibration cases from the decided cases of a run (``{dispute id: (category, outcome)}``).

    Within each category the first ``per_category`` by rank. ADR 0009 wants merchant-request decisions among them so
    that criterion 3 has cases: while there are fewer than ``min_merchant_requests``, the lowest-ranked other pick of
    a category is swapped for that category's best-ranked merchant request not yet picked, going round the categories
    in name order. Deterministic, and independent of file order.
    """
    by_category: dict[str, list[str]] = {}
    for dispute_id, (category, _) in decided.items():
        by_category.setdefault(category, []).append(dispute_id)
    picked = {c: sorted(ids, key=_rank)[:per_category] for c, ids in sorted(by_category.items())}

    def requests() -> int:
        return sum(decided[i][1] == MERCHANT_REQUEST for ids in picked.values() for i in ids)

    swapped = True
    while requests() < min_merchant_requests and swapped:
        swapped = False
        for category, ids in picked.items():
            if requests() >= min_merchant_requests:
                break
            spare = [
                i
                for i in sorted(by_category[category], key=_rank)
                if decided[i][1] == MERCHANT_REQUEST and i not in ids
            ]
            others = [i for i in ids if decided[i][1] != MERCHANT_REQUEST]
            if spare and others:
                ids[ids.index(others[-1])] = spare[0]
                swapped = True
    return sorted(i for ids in picked.values() for i in ids)


def labels_template(packets: list[dict[str, Any]], run: str) -> dict[str, Any]:
    """The file a person fills in: ``pass`` or ``fail`` for each criterion (``na`` is already set where the
    merchant criterion does not apply)."""
    return {
        "rubric_version": RUBRIC_VERSION,
        "run": run,
        "instructions": "Label each criterion pass or fail from calibration/sheet.md and rubric/rubric-v1.md, "
        "before looking at any grader answer. Commit the labels on their own before running agree.",
        "cases": {
            p["dispute_id"]: {
                "faithful": None,
                "evidence_check_complete": None,
                "merchant_request_clear": None if p["decision"]["outcome"] == MERCHANT_REQUEST else "na",
            }
            for p in packets
        },
    }


def sheet(packets: list[dict[str, Any]], run: str) -> str:
    """The labelling sheet: the same inputs the grader sees, and no grader output."""
    parts = [
        f"# Rubric calibration sheet ({RUBRIC_VERSION}, run {run})\n",
        f"{len(packets)} cases. For each, read the case below and label the three criteria of "
        f"`rubric/{RUBRIC_VERSION}.md` in `labels.json`. Do not open `rubric/answers/` before the labels are "
        "committed.\n",
    ]
    for p in packets:
        parts.append(f"## {p['dispute_id']} ({p['category']}, {p['decision']['outcome']})\n")
        parts.append("````text\n" + render(p) + "\n````\n")
    return "\n".join(parts)


def kappa(table: dict[str, int]) -> float | None:
    """Cohen's kappa of a 2x2 table; None when chance agreement is total (one label everywhere)."""
    a, b = table["both_pass"], table["human_pass_grader_fail"]
    c, d = table["human_fail_grader_pass"], table["both_fail"]
    n = a + b + c + d
    if n == 0:
        return None
    observed = (a + d) / n
    chance = ((a + b) * (a + c) + (c + d) * (b + d)) / (n * n)
    return None if chance == 1 else round((observed - chance) / (1 - chance), 4)


def agreement(labels: dict[str, Any], answers: list[dict[str, Any]]) -> dict[str, Any]:
    """Per criterion: the cases both the person and the grader scored pass or fail, raw agreement, Cohen's kappa, the
    2x2 table, and whether the criterion is calibrated well enough to publish (ADR 0009)."""
    missing = [i for i, ls in labels["cases"].items() for c in CRITERIA if ls[c] not in VERDICTS]
    if missing:
        raise ValueError(f"unlabelled: {sorted(set(missing))}")
    by_id = {a["dispute_id"]: a for a in answers}
    absent = sorted(set(labels["cases"]) - by_id.keys())
    if absent:
        raise ValueError(f"no grader answer for: {absent}")
    out: dict[str, Any] = {"rubric_version": RUBRIC_VERSION, "run": labels["run"], "criteria": {}}
    # Who made the labels travels with the agreement: "human" in the table names means the labeller.
    if "labelled_by" in labels:
        out["labelled_by"] = labels["labelled_by"]
    for c in CRITERIA:
        table = dict.fromkeys(("both_pass", "human_pass_grader_fail", "human_fail_grader_pass", "both_fail"), 0)
        excluded = 0
        for dispute_id, ls in sorted(labels["cases"].items()):
            scores = by_id[dispute_id]["scores"]
            human, grader = ls[c], scores[c]["verdict"] if scores else None
            if human == "na" or grader not in ("pass", "fail"):
                excluded += human != "na"
                continue
            key = {
                ("pass", "pass"): "both_pass",
                ("pass", "fail"): "human_pass_grader_fail",
                ("fail", "pass"): "human_fail_grader_pass",
                ("fail", "fail"): "both_fail",
            }[(human, grader)]
            table[key] += 1
        n = sum(table.values())
        raw = round((table["both_pass"] + table["both_fail"]) / n, 4) if n else None
        k = kappa(table)
        out["criteria"][c] = {
            "applicable": n,
            "excluded": excluded,
            "agreement": raw,
            "kappa": k,
            "table": table,
            "calibrated": raw is not None and k is not None and raw >= MIN_AGREEMENT and k >= MIN_KAPPA,
        }
    return out


# Command line -----------------------------------------------------------------------------------------------------


def _write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8", newline="\n")


def decided_packets(golden: Path, results_dir: Path, fixtures: Path, corpus: Path) -> list[dict[str, Any]]:
    """A packet for every golden case of the run that has a decision, in dispute-id order."""
    cases = json.loads(golden.read_text(encoding="utf-8"))["cases"]
    results = graders.load_results(results_dir)
    requests = last_requests(fixtures)
    sections = policy_sections(corpus)
    packets = []
    for case in sorted(cases, key=lambda c: c["dispute_id"]):
        result = results.get(case["dispute_id"])
        if result is None or result["outcome"].get("decision") is None:
            continue
        if case["dispute_id"] not in requests:
            raise SystemExit(f"{case['dispute_id']}: no recorded investigation request in {fixtures}")
        packets.append(
            case_packet(case["dispute_id"], case["category"], result, requests[case["dispute_id"]], sections)
        )
    return packets


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    def inputs(p: argparse.ArgumentParser) -> None:
        p.add_argument("--golden", type=Path, default=ROOT / "golden" / "all-categories-v2.json")
        p.add_argument("--results", type=Path, default=ROOT / "runs" / "recording")
        p.add_argument("--fixtures", type=Path, default=ROOT / "fixtures" / "llm")
        p.add_argument("--corpus", type=Path, default=graders.REPOSITORY / "data" / "policy-corpus")

    grade = commands.add_parser("grade", help="grade the decided cases of a run (a dry run without --live)")
    inputs(grade)
    grade.add_argument("--answers", type=Path, help="default: rubric/answers/<results directory name>")
    grade.add_argument("--model", default=os.environ.get("RUBRIC_MODEL", DEFAULT_MODEL))
    grade.add_argument("--effort", default=os.environ.get("RUBRIC_EFFORT", DEFAULT_EFFORT))
    grade.add_argument("--max-cost-usd", type=float, default=10.0)
    grade.add_argument("--live", action="store_true", help="call the grader model: costs money")

    summary = commands.add_parser("summary", help="summarise stored answers")
    summary.add_argument("--answers", type=Path, required=True)

    calibrate = commands.add_parser("pick", help="pick the calibration cases and write the blind labelling sheet")
    inputs(calibrate)
    calibrate.add_argument("--out", type=Path, default=RUBRIC_DIR / "calibration")

    agree = commands.add_parser("agree", help="agreement between the hand labels and the grader")
    agree.add_argument("--labels", type=Path, default=RUBRIC_DIR / "calibration" / "labels.json")
    agree.add_argument("--answers", type=Path, required=True)
    agree.add_argument("--out", type=Path, default=RUBRIC_DIR / "calibration" / "agreement.json")

    args = parser.parse_args(argv)
    if args.command == "grade":
        packets = decided_packets(args.golden, args.results, args.fixtures, args.corpus)
        answers = args.answers or RUBRIC_DIR / "answers" / args.results.name
        estimate = estimate_micro_usd(packets, args.model)
        print(f"{len(packets)} decided cases; {args.model} at {args.effort}; estimated ${estimate / 1e6:.2f}")
        if not args.live:
            print("dry run: nothing sent (add --live to grade; it costs money)")
            return 0
        grader = AnthropicGrader(args.model, args.effort, api_key())
        run = grade_run(packets, grader, answers, args.model, args.effort, round(args.max_cost_usd * 1e6))
        print(
            f"graded {run.graded}, reused {run.reused}, unscored {run.unscored}, ${run.cost_micro_usd / 1e6:.4f}"
            + (" (stopped at the cap)" if run.capped else "")
        )
        return 1 if run.capped else 0
    if args.command == "summary":
        print(json.dumps(summarise(load_answers(args.answers)), indent=2))
        return 0
    if args.command == "pick":
        packets = decided_packets(args.golden, args.results, args.fixtures, args.corpus)
        chosen = set(pick({p["dispute_id"]: (p["category"], p["decision"]["outcome"]) for p in packets}))
        picked = [p for p in packets if p["dispute_id"] in chosen]
        labels = args.out / "labels.json"
        if labels.exists() and any(
            v not in (None, "na")
            for ls in json.loads(labels.read_text(encoding="utf-8"))["cases"].values()
            for v in ls.values()
        ):
            raise SystemExit(f"{labels} already holds labels: not overwritten")
        (args.out).mkdir(parents=True, exist_ok=True)
        (args.out / "sheet.md").write_text(sheet(picked, args.results.name), encoding="utf-8", newline="\n")
        _write(labels, labels_template(picked, args.results.name))
        print(f"{len(picked)} cases: {args.out / 'sheet.md'}, labels to fill in {labels}")
        return 0
    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    result = agreement(labels, load_answers(args.answers))
    _write(args.out, result)
    for c, r in result["criteria"].items():
        state = "calibrated" if r["calibrated"] else "not calibrated well enough to report"
        print(f"{c:24} n={r['applicable']:>2} agreement {r['agreement']} kappa {r['kappa']}: {state}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
