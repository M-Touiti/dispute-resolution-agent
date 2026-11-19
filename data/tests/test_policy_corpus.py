"""The policy corpus must state the rules the ground truth is generated from (datagen/policy.py)."""

import re
from dataclasses import dataclass
from pathlib import Path

import pytest

from datagen import policy
from datagen.generator import SCENARIOS
from datagen.model import Category

CORPUS = Path(__file__).parents[1] / "policy-corpus"
ALL = "all categories"

METADATA = re.compile(r"^Section `([a-z0-9]+(?:-[a-z0-9]+)*)`; version ([1-9][0-9]*); applies to: (.+)$")


@dataclass(frozen=True)
class Section:
    file: str
    section_id: str
    title: str
    version: int
    applies_to: tuple[str, ...]
    text: str


def _parse(path: Path) -> list[Section]:
    lines = path.read_text(encoding="utf-8").splitlines()
    assert lines[0].startswith("# "), f"{path.name}: first line must be the # title"
    sections: list[Section] = []
    starts = [i for i, line in enumerate(lines) if line.startswith("## ")]
    for n, start in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        assert lines[start + 1] == "", f"{path.name}:{start + 2}: blank line expected after the heading"
        meta = METADATA.match(lines[start + 2])
        assert meta, f"{path.name}:{start + 3}: metadata line expected, got {lines[start + 2]!r}"
        applies = meta.group(3)
        text = "\n".join(lines[start + 3 : end]).strip()
        assert text, f"{path.name}: section {meta.group(1)} has no text"
        sections.append(
            Section(
                file=path.name,
                section_id=meta.group(1),
                title=lines[start][3:].strip(),
                version=int(meta.group(2)),
                applies_to=(ALL,) if applies == ALL else tuple(c.strip() for c in applies.split(",")),
                text=text,
            )
        )
    return sections


def _corpus() -> list[Section]:
    files = sorted(p for p in CORPUS.glob("*.md") if p.name != "README.md")
    assert files, "no corpus files"
    return [s for f in files for s in _parse(f)]


SECTIONS = _corpus()
BY_ID = {s.section_id: s for s in SECTIONS}


def test_section_ids_are_unique() -> None:
    ids = [s.section_id for s in SECTIONS]
    assert len(ids) == len(set(ids)), sorted(i for i in ids if ids.count(i) > 1)


def test_applies_to_names_real_categories() -> None:
    valid = {c.value for c in Category}
    for s in SECTIONS:
        if s.applies_to != (ALL,):
            assert set(s.applies_to) <= valid, (s.section_id, s.applies_to)


def test_every_category_has_its_own_sections() -> None:
    covered = {c for s in SECTIONS for c in s.applies_to}
    assert {c.value for c in Category} <= covered


@pytest.mark.parametrize("scenario", [s.name for s in SCENARIOS])
def test_every_scenario_has_a_deciding_section_that_applies_to_it(scenario: str) -> None:
    category = next(s.category for s in SCENARIOS if s.name == scenario)
    section = BY_ID.get(policy.DECIDING_SECTION[scenario])

    assert section is not None, f"{scenario}: no section {policy.DECIDING_SECTION[scenario]!r}"
    assert section.applies_to == (ALL,) or category.value in section.applies_to


def test_deciding_sections_cover_exactly_the_generated_scenarios() -> None:
    assert set(policy.DECIDING_SECTION) == {s.name for s in SCENARIOS}


def test_the_section_ids_named_in_tool_descriptions_exist() -> None:
    # policy-mcp and actions-mcp give `time-limits` as the example section id.
    assert "time-limits" in BY_ID


# Numbers ------------------------------------------------------------------------------------------------------------


def _eur(minor: int) -> str:
    return f"{minor // 100:,}.{minor % 100:02d} EUR"


ALLOWED_NUMBERS = {
    f"{policy.TIME_LIMIT_DAYS} days": "TIME_LIMIT_DAYS",
    f"{policy.GOODS_NOT_RECEIVED_WAIT_DAYS} days": "GOODS_NOT_RECEIVED_WAIT_DAYS",
    _eur(policy.PROVISIONAL_CREDIT_LIMIT_MINOR): "PROVISIONAL_CREDIT_LIMIT_MINOR",
    f"{policy.TIP_TOLERANCE_PERCENT}%": "TIP_TOLERANCE_PERCENT",
}
NUMBER = re.compile(r"\d[\d,.]*\d|\d")
LIST_ITEM = re.compile(r"^\d+\. ", re.MULTILINE)


def _numbers_outside_known_values(text: str) -> list[str]:
    text = LIST_ITEM.sub("", text).replace("3-D Secure", "")
    for value in ALLOWED_NUMBERS:
        text = text.replace(value, "")
    return NUMBER.findall(text)


def test_every_number_in_the_corpus_is_a_policy_value() -> None:
    stray = {s.section_id: found for s in SECTIONS if (found := _numbers_outside_known_values(s.text))}
    assert not stray, f"numbers that are not policy values (write them as {sorted(ALLOWED_NUMBERS)}): {stray}"


@pytest.mark.parametrize("value", sorted(ALLOWED_NUMBERS))
def test_every_policy_value_is_stated(value: str) -> None:
    assert any(value in s.text for s in SECTIONS), f"{ALLOWED_NUMBERS[value]} ({value}) is not in the corpus"


def test_time_limit_is_in_the_time_limits_section() -> None:
    assert f"{policy.TIME_LIMIT_DAYS} days" in BY_ID["time-limits"].text
    assert _eur(policy.PROVISIONAL_CREDIT_LIMIT_MINOR) in BY_ID["provisional-credit"].text


# Actions ------------------------------------------------------------------------------------------------------------


def test_the_corpus_names_exactly_the_policy_actions() -> None:
    named = {a for s in SECTIONS for a in re.findall(r"`([a-z]+(?:_[a-z]+)+)`", s.text)}
    assert named == set(policy.ACTIONS)
