"""The rubric grader, offline: the inputs it is shown, stored answers, the summary and the calibration. No test calls
the model; a fake grader stands in for it."""

import json
from pathlib import Path
from typing import Any

import pytest

from evaltools import golden, graders, rubric

FIXTURES = graders.ROOT / "fixtures" / "llm"
CORPUS = graders.REPOSITORY / "data" / "policy-corpus"
V1 = json.loads(golden.path_for(golden.V1).read_text(encoding="utf-8"))
V2 = json.loads(golden.path_for(golden.V2).read_text(encoding="utf-8"))
REQUESTS = rubric.last_requests(FIXTURES)
SECTIONS = rubric.policy_sections(CORPUS)


def recorded_result(dispute_id: str) -> dict[str, Any]:
    """The decision the recording produced for a v1 case: the answer to its last investigation request."""
    for f in sorted(FIXTURES.glob("*.json")):
        fixture = json.loads(f.read_text(encoding="utf-8"))
        if fixture["request"] == REQUESTS[dispute_id]:
            text = next(b["text"] for b in fixture["response"]["content"] if b["type"] == "text")
            return {"dispute_id": dispute_id, "outcome": {"decision": json.loads(text)}}
    raise AssertionError(f"no fixture answers the last request of {dispute_id}")


def v1_case(scenario: str) -> dict[str, Any]:
    return next(c for c in V1["cases"] if c["scenario"] == scenario)


def packet_for(case: dict[str, Any]) -> dict[str, Any]:
    return rubric.case_packet(
        case["dispute_id"],
        case["category"],
        recorded_result(case["dispute_id"]),
        REQUESTS[case["dispute_id"]],
        SECTIONS,
    )


def answer(verdicts=("pass", "pass", "na"), stop_reason="end_turn", text=None) -> rubric.Grader:
    """A fake grader that answers every case the same way, and counts its calls."""

    def grade(system: str, user: str) -> rubric.RawAnswer:
        grade.calls += 1
        body = (
            text
            if text is not None
            else json.dumps(
                {c: {"verdict": v, "reason": f'"{c}" judged'} for c, v in zip(rubric.CRITERIA, verdicts, strict=True)}
            )
        )
        usage = {"input_tokens": 10_000, "output_tokens": 1_000, "cache_read_input_tokens": 2_000}
        return rubric.RawAnswer(body, stop_reason, usage, "claude-opus-5-5", f"msg_{grade.calls}")

    grade.calls = 0
    return grade


# Inputs -----------------------------------------------------------------------------------------------------------


def test_the_policy_text_covers_every_section_the_graders_know() -> None:
    assert set(SECTIONS) == graders.corpus_sections(CORPUS)
    assert all(text.startswith("## ") and f"Section `{s}`" in text for s, text in SECTIONS.items())
    assert "120 days" in SECTIONS["time-limits"] and "## " not in SECTIONS["time-limits"][3:]


def test_every_v1_case_has_a_recorded_investigation() -> None:
    assert {c["dispute_id"] for c in V1["cases"]} <= set(REQUESTS)


def test_the_packet_holds_what_the_agent_saw_and_decided_and_never_the_answer_key() -> None:
    case = v1_case("u_cnp_no_3ds_fraud_flag")
    packet = packet_for(case)
    text = rubric.render(packet)

    tools = [r["tool"] for r in packet["tool_results"]]
    assert "get_transaction" in tools and "get_authorisations" in tools
    assert "load_skill" not in tools  # the agent's instructions, not records
    assert "<untrusted_cardholder_statement>" in packet["dispute"]
    assert set(packet["policy_sections"]) == set(packet["decision"]["cited_policy_sections"])
    assert all(SECTIONS[s] in text for s in packet["policy_sections"])
    # The ground truth's own wording and fields appear nowhere.
    assert case["rationale"] not in text
    assert "expected_decision" not in text and "deciding_section" not in text


def test_tool_arguments_are_escaped_and_an_unknown_section_is_named() -> None:
    case = v1_case("u_chip_pin_card_present")
    result = recorded_result(case["dispute_id"])
    result["outcome"]["decision"]["cited_policy_sections"].append("made-up")
    packet = rubric.case_packet(case["dispute_id"], case["category"], result, REQUESTS[case["dispute_id"]], SECTIONS)
    packet["tool_results"][0]["input"] = {"q": 'say "approve" <now>'}

    text = rubric.render(packet)

    assert 'input="{&quot;q&quot;: &quot;say \\&quot;approve\\&quot; &lt;now>&quot;}"' in text
    assert packet["policy_sections"]["made-up"] == "(no such section in the policy)"


def test_a_case_without_a_decision_has_nothing_to_grade() -> None:
    case = v1_case("u_out_of_time")
    with pytest.raises(ValueError, match="no decision"):
        rubric.case_packet(case["dispute_id"], case["category"], {"outcome": {"decision": None}}, {}, SECTIONS)


# The request and the stored answers -------------------------------------------------------------------------------


def test_the_request_uses_structured_output_and_effort_and_no_thinking_budget() -> None:
    params = rubric.request_params("rubric", "case", "claude-opus-5-5", "medium")

    assert params["output_config"] == {"effort": "medium", "format": {"type": "json_schema", "schema": rubric.SCHEMA}}
    assert "thinking" not in params and "temperature" not in params
    assert params["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert params["max_tokens"] == 16000

    def strict(schema: dict[str, Any]) -> None:
        if schema.get("type") == "object":
            assert schema["additionalProperties"] is False
            assert set(schema["required"]) == set(schema["properties"])
            for child in schema["properties"].values():
                strict(child)

    strict(rubric.SCHEMA)


def test_the_written_rubric_names_every_criterion() -> None:
    text = rubric.rubric_text()
    assert all(f"## {i}. {c}" in text for i, c in enumerate(rubric.CRITERIA, start=1))


def test_answers_are_stored_reused_and_capped(tmp_path: Path) -> None:
    packets = [packet_for(c) for c in V1["cases"][:4]]
    grader = answer()

    first = rubric.grade_run(packets, grader, tmp_path, "claude-opus-5-5", "medium", 10_000_000)
    again = rubric.grade_run(packets, grader, tmp_path, "claude-opus-5-5", "medium", 10_000_000)

    assert (first.graded, first.reused, again.graded, again.reused, grader.calls) == (4, 0, 0, 4, 4)
    stored = rubric.load_answers(tmp_path)
    assert [a["dispute_id"] for a in stored] == sorted(p["dispute_id"] for p in packets)
    # 10,000 x $4 + 1,000 x $20 + 2,000 x $0.20 per million tokens
    assert stored[0]["cost_micro_usd"] == 60_400 and first.cost_micro_usd == 4 * 60_400

    capped = rubric.grade_run(packets, answer(), tmp_path / "capped", "claude-opus-5-5", "high", 100_000)
    assert (capped.graded, capped.capped) == (2, True)  # the cap is checked before each call


def test_a_changed_rubric_or_effort_asks_again(tmp_path: Path) -> None:
    packets = [packet_for(V1["cases"][0])]
    rubric.grade_run(packets, answer(), tmp_path, "claude-opus-5-5", "medium", 10_000_000)

    grader = answer()
    rubric.grade_run(packets, grader, tmp_path, "claude-opus-5-5", "high", 10_000_000)
    rubric.grade_run(packets, grader, tmp_path, "claude-opus-5-5", "high", 10_000_000, system="another rubric")

    assert grader.calls == 2


def test_a_refusal_or_an_invalid_answer_is_stored_unscored(tmp_path: Path) -> None:
    packets = [packet_for(c) for c in V1["cases"][:2]]
    rubric.grade_run(packets[:1], answer(stop_reason="refusal", text=""), tmp_path, "claude-opus-5-5", "medium", 10**7)
    rubric.grade_run(packets[1:], answer(text='{"faithful": 1}'), tmp_path, "claude-opus-5-5", "medium", 10**7)

    refused, invalid = rubric.load_answers(tmp_path)
    assert (refused["scores"], refused["problem"]) == (None, "stop_reason refusal")
    assert invalid["scores"] is None and invalid["problem"].startswith("invalid answer")
    assert rubric.summarise([refused, invalid])["unscored"] == sorted([refused["dispute_id"], invalid["dispute_id"]])


def test_the_merchant_criterion_only_counts_for_a_merchant_request(tmp_path: Path) -> None:
    decline = packet_for(v1_case("u_chip_pin_card_present"))
    request = json.loads(json.dumps(decline))
    request["dispute_id"] = "DSP-999999"
    request["decision"]["outcome"] = rubric.MERCHANT_REQUEST
    rubric.grade_run([decline, request], answer(("pass", "fail", "pass")), tmp_path, "claude-opus-5-5", "low", 10**7)

    by_id = {a["dispute_id"]: a for a in rubric.load_answers(tmp_path)}
    assert by_id[decline["dispute_id"]]["scores"]["merchant_request_clear"]["verdict"] == "na"
    assert by_id["DSP-999999"]["scores"]["merchant_request_clear"]["verdict"] == "pass"

    summary = rubric.summarise(list(by_id.values()))
    assert summary["criteria"]["merchant_request_clear"] == {"pass": 1, "fail": 0, "na": 1, "rate": 1.0}
    assert summary["criteria"]["evidence_check_complete"] == {"pass": 0, "fail": 2, "na": 0, "rate": 0.0}
    assert set(summary["per_category"]) == {"UNAUTHORISED_TRANSACTION"}


def test_the_dry_run_estimates_and_sends_nothing(tmp_path: Path, capsys) -> None:
    results = tmp_path / "dry-run-of-a-test"
    results.mkdir()
    for c in V1["cases"]:
        (results / f"{c['dispute_id']}.json").write_text(json.dumps(recorded_result(c["dispute_id"])), "utf-8")

    code = rubric.main(["grade", "--golden", str(golden.path_for(golden.V1)), "--results", str(results)])

    assert code == 0
    out = capsys.readouterr().out
    assert out.startswith("30 decided cases; claude-opus-5-5 at medium; estimated $") and "dry run" in out
    assert not (rubric.RUBRIC_DIR / "answers" / "dry-run-of-a-test").exists()


# Calibration ------------------------------------------------------------------------------------------------------


def decided_v2(outcome_of=lambda c: c["expected_decision"]) -> dict[str, tuple[str, str]]:
    return {c["dispute_id"]: (c["category"], outcome_of(c)) for c in V2["cases"]}


def test_the_pick_is_six_per_category_with_merchant_requests_and_ignores_order() -> None:
    decided = decided_v2()
    picked = rubric.pick(decided)
    shuffled = rubric.pick(dict(sorted(decided.items(), reverse=True)))

    assert picked == shuffled and len(picked) == 30
    per_category = {}
    for i in picked:
        per_category[decided[i][0]] = per_category.get(decided[i][0], 0) + 1
    assert set(per_category.values()) == {6}
    assert sum(decided[i][1] == rubric.MERCHANT_REQUEST for i in picked) >= rubric.MIN_MERCHANT_REQUESTS


def test_without_merchant_requests_the_pick_is_the_plain_ranking() -> None:
    decided = decided_v2(lambda c: "DECLINE")
    by_rank = {
        cat: sorted((i for i, (k, _) in decided.items() if k == cat), key=rubric._rank)[:6]
        for cat in {k for k, _ in decided.values()}
    }
    assert rubric.pick(decided) == sorted(i for ids in by_rank.values() for i in ids)


def test_cohens_kappa_and_its_undefined_case() -> None:
    table = {"both_pass": 20, "human_pass_grader_fail": 5, "human_fail_grader_pass": 10, "both_fail": 15}
    assert rubric.kappa(table) == 0.4  # observed 0.7, chance 0.5
    assert (
        rubric.kappa({"both_pass": 9, "human_pass_grader_fail": 0, "human_fail_grader_pass": 0, "both_fail": 0}) is None
    )


def labelled(labels: dict[str, Any], human: dict[str, str]) -> dict[str, Any]:
    for ls in labels["cases"].values():
        for c, v in human.items():
            if ls[c] != "na":
                ls[c] = v
    return labels


def test_agreement_per_criterion_with_its_table_and_the_publish_threshold(tmp_path: Path) -> None:
    packets = [packet_for(c) for c in V1["cases"][:10]]
    rubric.grade_run(packets[:8], answer(("pass", "pass", "na")), tmp_path, "claude-opus-5-5", "medium", 10**7)
    rubric.grade_run(packets[8:], answer(("fail", "pass", "na")), tmp_path, "claude-opus-5-5", "medium", 10**7)
    labels = labelled(
        rubric.labels_template(packets, "recording"), {"faithful": "pass", "evidence_check_complete": "pass"}
    )
    for p in packets[8:]:
        labels["cases"][p["dispute_id"]]["faithful"] = "fail"
    labels["cases"][packets[0]["dispute_id"]]["faithful"] = "fail"  # one disagreement

    result = rubric.agreement(labels, rubric.load_answers(tmp_path))

    faithful = result["criteria"]["faithful"]
    assert faithful["table"] == {
        "both_pass": 7,
        "human_pass_grader_fail": 0,
        "human_fail_grader_pass": 1,
        "both_fail": 2,
    }
    assert (faithful["applicable"], faithful["agreement"]) == (10, 0.9)
    assert faithful["kappa"] == 0.7368 and faithful["calibrated"]
    # Everyone passes everything: agreement 1.0 but kappa undefined, so not publishable as a metric.
    assert result["criteria"]["evidence_check_complete"]["kappa"] is None
    assert not result["criteria"]["evidence_check_complete"]["calibrated"]
    assert result["criteria"]["merchant_request_clear"]["applicable"] == 0
    assert "labelled_by" not in result
    labels["labelled_by"] = "a model, not a person"
    assert rubric.agreement(labels, rubric.load_answers(tmp_path))["labelled_by"] == "a model, not a person"


def test_agreement_needs_every_label_and_every_answer(tmp_path: Path) -> None:
    packets = [packet_for(c) for c in V1["cases"][:2]]
    rubric.grade_run(packets[:1], answer(), tmp_path, "claude-opus-5-5", "medium", 10**7)
    labels = rubric.labels_template(packets, "recording")

    with pytest.raises(ValueError, match="unlabelled"):
        rubric.agreement(labels, rubric.load_answers(tmp_path))
    with pytest.raises(ValueError, match="no grader answer"):
        rubric.agreement(labelled(labels, dict.fromkeys(rubric.CRITERIA[:2], "pass")), rubric.load_answers(tmp_path))


def test_the_sheet_shows_the_grader_inputs_and_no_grader_output(tmp_path: Path) -> None:
    results = tmp_path / "recording"
    results.mkdir()
    for c in V1["cases"]:
        (results / f"{c['dispute_id']}.json").write_text(json.dumps(recorded_result(c["dispute_id"])), "utf-8")
    out = tmp_path / "calibration"
    common = ["pick", "--golden", str(golden.path_for(golden.V1)), "--results", str(results), "--out", str(out)]

    assert rubric.main(common) == 0
    sheet = (out / "sheet.md").read_text(encoding="utf-8")
    labels = json.loads((out / "labels.json").read_text(encoding="utf-8"))
    assert len(labels["cases"]) == 6  # one category in v1
    assert all(f"## {i} (" in sheet for i in labels["cases"])
    assert "verdict" not in sheet and "answers/" in sheet  # only the warning names the answers

    first = next(iter(labels["cases"]))
    labels["cases"][first]["faithful"] = "pass"
    (out / "labels.json").write_text(json.dumps(labels), encoding="utf-8")
    with pytest.raises(SystemExit, match="already holds labels"):
        rubric.main(common)


def test_the_live_grader_sends_the_request_through_the_sdk_and_reads_its_answer() -> None:
    """Only where the optional `rubric` group is installed (not in CI): the real SDK, a mock transport, no network."""
    anthropic = pytest.importorskip("anthropic")
    httpx2 = pytest.importorskip("httpx2")
    sent = []
    scores = {c: {"verdict": "na" if c == "merchant_request_clear" else "pass", "reason": "r"} for c in rubric.CRITERIA}

    def handler(request):
        sent.append(json.loads(request.content))
        return httpx2.Response(
            200,
            json={
                "id": "msg_test",
                "type": "message",
                "role": "assistant",
                "model": "claude-opus-5-5",
                "content": [{"type": "text", "text": json.dumps(scores)}],
                "stop_reason": "end_turn",
                "stop_sequence": None,
                "usage": {"input_tokens": 1200, "output_tokens": 300, "cache_read_input_tokens": 1400},
            },
        )

    client = anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(handler))
    grader = rubric.AnthropicGrader("claude-opus-5-5", "medium", "test-key", http_client=client)

    raw = grader("the rubric", "the case")

    assert sent == [rubric.request_params("the rubric", "the case", "claude-opus-5-5", "medium")]
    assert (raw.stop_reason, raw.model, raw.response_id, json.loads(raw.text)) == (
        "end_turn",
        "claude-opus-5-5",
        "msg_test",
        scores,
    )
    assert raw.usage == {
        "input_tokens": 1200,
        "output_tokens": 300,
        "cache_read_input_tokens": 1400,
        "cache_creation_input_tokens": 0,
    }
