"""The two read-only policy tools.

Input schemas live in ``policy_mcp/tools/<name>.schema.json`` and stay usable as strict Messages API tools:
``additionalProperties: false``, no numeric or length constraints. Changing a name, description or schema changes
``tools/list``: update ``tests/contract/tools-list.json`` in the same pull request and re-record the investigator's
fixtures.
"""

import json
from dataclasses import dataclass
from functools import cache
from importlib import resources
from typing import Any

from mcp_types import Tool, ToolAnnotations

NOT_IMPLEMENTED = "NOT_IMPLEMENTED: policy-mcp tools are stubs until milestone M1."


@dataclass(frozen=True, slots=True)
class PolicyTool:
    name: str
    title: str
    description: str

    @property
    def input_schema(self) -> dict[str, Any]:
        return _schema(self.name)

    def to_mcp(self) -> Tool:
        return Tool(
            name=self.name,
            title=self.title,
            description=self.description,
            input_schema=self.input_schema,
            annotations=ToolAnnotations(
                title=self.title,
                read_only_hint=True,
                destructive_hint=False,
                idempotent_hint=True,
                open_world_hint=False,
            ),
        )


TOOLS: tuple[PolicyTool, ...] = tuple(
    sorted(
        (
            PolicyTool(
                name="search_policy",
                title="Search policy",
                description=(
                    "Search the card dispute policy for the sections relevant to a question, best match first. "
                    "Combines keyword and semantic search. Each hit has a section id, title, the matching passage "
                    "and the categories it applies to. Cite section ids in decisions."
                ),
            ),
            PolicyTool(
                name="get_policy_section",
                title="Get policy section",
                description=(
                    "Get the full text of one policy section by id, with its title, version and the dispute "
                    "categories it applies to."
                ),
            ),
        ),
        key=lambda t: t.name,
    )
)

TOOLS_BY_NAME: dict[str, PolicyTool] = {t.name: t for t in TOOLS}


@cache
def _schema(name: str) -> dict[str, Any]:
    text = resources.files("policy_mcp").joinpath("tools", f"{name}.schema.json").read_text(encoding="utf-8")
    return json.loads(text)
