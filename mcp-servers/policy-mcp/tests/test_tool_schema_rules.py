"""The schemas are passed to Claude as strict tools, so they stay inside the JSON Schema subset strict mode accepts."""

import re
import tomllib
from pathlib import Path
from typing import Any

import pytest

from policy_mcp import __version__
from policy_mcp.tools import TOOLS, TOOLS_BY_NAME, PolicyTool

UNSUPPORTED_IN_STRICT_MODE = {
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    "minLength", "maxLength", "pattern", "default", "minItems", "maxItems", "uniqueItems",
}  # fmt: skip


def _keywords(node: Any) -> set[str]:
    if isinstance(node, dict):
        return set(node) | {k for v in node.values() for k in _keywords(v)}
    if isinstance(node, list):
        return {k for v in node for k in _keywords(v)}
    return set()


@pytest.mark.parametrize("tool", TOOLS, ids=lambda t: t.name)
def test_schema_is_a_strict_compatible_closed_object(tool: PolicyTool) -> None:
    schema = tool.input_schema

    assert schema["type"] == "object"
    assert schema["additionalProperties"] is False
    assert schema["required"]
    assert set(schema["required"]) <= set(schema["properties"])
    for name, prop in schema["properties"].items():
        assert "type" in prop, name
        assert prop["description"].strip(), name


@pytest.mark.parametrize("tool", TOOLS, ids=lambda t: t.name)
def test_schema_uses_no_keyword_strict_mode_rejects(tool: PolicyTool) -> None:
    assert not _keywords(tool.input_schema) & UNSUPPORTED_IN_STRICT_MODE


def test_tool_names_are_unique_snake_case_and_sorted() -> None:
    names = [t.name for t in TOOLS]

    assert names == sorted(set(names))
    assert all(re.fullmatch(r"[a-z]+(_[a-z]+)*", n) for n in names)
    assert names == ["get_policy_section", "search_policy"]


def test_category_enum_matches_the_data_generator() -> None:
    model = (Path(__file__).parents[3] / "data" / "datagen" / "model.py").read_text(encoding="utf-8")
    block = model.split("class Category(StrEnum):", 1)[1].split("\n\n", 1)[0]
    categories = sorted(re.findall(r'^\s+[A-Z_]+ = "([A-Z_]+)"', block, flags=re.MULTILINE))

    assert categories
    assert TOOLS_BY_NAME["search_policy"].input_schema["properties"]["category"]["enum"] == categories


def test_version_matches_pyproject() -> None:
    pyproject = tomllib.loads((Path(__file__).parents[1] / "pyproject.toml").read_text(encoding="utf-8"))

    assert __version__ == pyproject["project"]["version"]
