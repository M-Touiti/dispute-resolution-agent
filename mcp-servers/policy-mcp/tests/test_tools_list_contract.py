"""Pins the ``tools/list`` output over real Streamable HTTP.

The contract is taken over the legacy initialize handshake, which is what the investigator's Java client
(MCP Java SDK 2.2.0, protocol 2025-11-25) uses. For an intended change run
``CONTRACT_UPDATE=1 uv run pytest tests/test_tools_list_contract.py``, review the diff of
``tests/contract/tools-list.json`` and commit it with the change.
"""

import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import pytest
from mcp import Client
from mcp_types import CallToolResult, TextContent, Tool

from policy_mcp import __version__

CONTRACT = Path(__file__).parent / "contract" / "tools-list.json"


def _canonical(tools: list[Tool]) -> str:
    dumped = [t.model_dump(by_alias=True, exclude_none=True, mode="json") for t in sorted(tools, key=lambda t: t.name)]
    return json.dumps(dumped, indent=2, sort_keys=True, ensure_ascii=True) + "\n"


def _text(result: CallToolResult) -> str:
    block = result.content[0]
    assert isinstance(block, TextContent)
    return block.text


@pytest.mark.anyio
async def test_tools_list_matches_the_contract(mcp_url: str) -> None:
    async with Client(mcp_url, mode="legacy") as client:
        actual = _canonical((await client.list_tools()).tools)

    if os.environ.get("CONTRACT_UPDATE") == "1":
        CONTRACT.parent.mkdir(parents=True, exist_ok=True)
        CONTRACT.write_text(actual, encoding="utf-8", newline="\n")

    assert actual == CONTRACT.read_text(encoding="utf-8")


@pytest.mark.anyio
async def test_modern_protocol_lists_the_same_tools(mcp_url: str) -> None:
    async with Client(mcp_url, mode="legacy") as legacy, Client(mcp_url) as modern:
        legacy_tools = _canonical((await legacy.list_tools()).tools)
        modern_tools = _canonical((await modern.list_tools()).tools)

    assert modern_tools == legacy_tools


@pytest.mark.anyio
async def test_server_identifies_itself_and_offers_only_tools(mcp_url: str) -> None:
    async with Client(mcp_url, mode="legacy") as client:
        info = client.server_info
        capabilities = client.server_capabilities

    assert info is not None
    assert (info.name, info.version) == ("policy-mcp", __version__)
    assert capabilities.tools is not None
    assert capabilities.resources is None
    assert capabilities.prompts is None


@pytest.mark.anyio
async def test_every_tool_is_read_only(mcp_url: str) -> None:
    async with Client(mcp_url, mode="legacy") as client:
        tools = (await client.list_tools()).tools

    for tool in tools:
        assert tool.annotations is not None, tool.name
        assert tool.annotations.read_only_hint is True, tool.name
        assert tool.annotations.destructive_hint is False, tool.name


@pytest.mark.anyio
async def test_search_answers_over_real_http(mcp_url: str) -> None:
    async with Client(mcp_url, mode="legacy") as client:
        result = await client.call_tool("search_policy", {"query": "time limit", "category": "DUPLICATE_CHARGE"})

    assert not result.is_error, _text(result)
    assert json.loads(_text(result))["results"]


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("tool", "arguments", "expected"),
    [
        ("get_policy_section", {"section": "time-limits"}, "section_id"),
        ("search_policy", {"query": "x", "category": "FRAUD"}, "category"),
        ("search_policy", {"query": 42}, "query"),
    ],
)
async def test_inputs_are_validated_against_the_schema(
    mcp_url: str, tool: str, arguments: dict[str, Any], expected: str
) -> None:
    async with Client(mcp_url, mode="legacy") as client:
        result = await client.call_tool(tool, arguments)

    assert result.is_error
    assert _text(result).startswith("INVALID_INPUT: ")
    assert expected in _text(result)


def test_health(base_url: str) -> None:
    with urllib.request.urlopen(base_url + "/health", timeout=5) as response:
        health = json.load(response)
    assert health["status"] == "UP"
    assert health["sections"] > 0
    assert health["embedding_model"].startswith("hash-bag-of-words/")


def test_a_foreign_host_header_is_rejected(base_url: str) -> None:
    request = urllib.request.Request(
        base_url + "/mcp",
        data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}).encode(),
        headers={
            "Host": "attacker.example",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
        method="POST",
    )
    with pytest.raises(urllib.error.HTTPError) as rejected:
        urllib.request.urlopen(request, timeout=5)

    assert rejected.value.code in (400, 403, 421)
