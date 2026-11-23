"""Tool behaviour over real Streamable HTTP, with the deterministic hash embedder and the real corpus."""

import json
from typing import Any

import pytest
from mcp import Client
from mcp_types import CallToolResult, TextContent

from policy_mcp.app import repository_corpus_dir
from policy_mcp.corpus import read_corpus
from policy_mcp.embedding import HashEmbedder
from policy_mcp.store import PolicyStore

pytestmark = pytest.mark.anyio

CORPUS = repository_corpus_dir()


def _text(result: CallToolResult) -> str:
    block = result.content[0]
    assert isinstance(block, TextContent)
    return block.text


async def _call(mcp_url: str, tool: str, args: dict[str, Any]) -> CallToolResult:
    async with Client(mcp_url, mode="legacy") as client:
        return await client.call_tool(tool, args)


async def _ok(mcp_url: str, tool: str, args: dict[str, Any]) -> dict[str, Any]:
    result = await _call(mcp_url, tool, args)
    assert not result.is_error, _text(result)
    return json.loads(_text(result))


async def test_get_policy_section_returns_the_full_section(mcp_url: str) -> None:
    section = await _ok(mcp_url, "get_policy_section", {"section_id": "time-limits"})

    assert section["title"] == "Time limit for opening a dispute"
    assert section["applies_to"] == ["ALL"]
    assert section["version"] == 1
    assert section["source_file"] == "general.md"
    assert "120 days" in section["text"]


async def test_search_finds_a_section_by_its_own_terms(mcp_url: str) -> None:
    found = await _ok(
        mcp_url, "search_policy", {"query": "3-D Secure authenticated payment", "category": "UNAUTHORISED_TRANSACTION"}
    )

    assert found["results"][0]["section_id"] == "unauthorised-3ds-authenticated"
    assert set(found["results"][0]["matched_by"]) <= {"full_text", "semantic"}


async def test_category_filter_keeps_that_category_and_general_sections(mcp_url: str) -> None:
    found = await _ok(
        mcp_url,
        "search_policy",
        {"query": "refund credit merchant evidence", "category": "DUPLICATE_CHARGE", "max_results": 10},
    )

    assert found["results"]
    for hit in found["results"]:
        assert hit["applies_to"] in (["ALL"], ["DUPLICATE_CHARGE"]), hit["section_id"]


async def test_max_results_and_default(mcp_url: str) -> None:
    two = await _ok(mcp_url, "search_policy", {"query": "dispute", "max_results": 2})
    default = await _ok(mcp_url, "search_policy", {"query": "dispute"})

    assert len(two["results"]) == 2
    assert len(default["results"]) == 5


async def test_the_same_query_returns_the_same_bytes(mcp_url: str) -> None:
    args = {"query": "cardholder did not recognise the online payment", "max_results": 10}
    first = await _call(mcp_url, "search_policy", args)
    second = await _call(mcp_url, "search_policy", args)

    assert _text(first) == _text(second)


@pytest.mark.parametrize(
    ("tool", "args", "prefix"),
    [
        ("get_policy_section", {"section_id": "no-such-section"}, "NOT_FOUND: no policy section no-such-section"),
        ("get_policy_section", {"section_id": "Time Limits"}, "INVALID_ARGUMENT: section_id"),
        ("search_policy", {"query": "   "}, "INVALID_ARGUMENT: query must not be empty"),
        ("search_policy", {"query": "x", "max_results": 0}, "INVALID_ARGUMENT: max_results"),
        ("search_policy", {"query": "x", "max_results": 11}, "INVALID_ARGUMENT: max_results"),
    ],
)
async def test_bad_arguments_return_a_tool_error(mcp_url: str, tool: str, args: dict[str, Any], prefix: str) -> None:
    result = await _call(mcp_url, tool, args)

    assert result.is_error
    assert _text(result).startswith(prefix)


def test_ingestion_runs_only_when_the_corpus_or_the_model_changes(scratch_database) -> None:
    from psycopg_pool import ConnectionPool

    sections = read_corpus(CORPUS)
    with ConnectionPool(scratch_database("ingestion"), min_size=1, max_size=2, open=True) as pool:
        store = PolicyStore(pool)

        first = store.ingest(sections, "corpus-a", HashEmbedder(256))
        again = store.ingest(sections, "corpus-a", HashEmbedder(256))
        new_model = store.ingest(sections, "corpus-a", HashEmbedder(128))
        new_corpus = store.ingest(sections[:3], "corpus-b", HashEmbedder(128))

        assert (first.changed, again.changed, new_model.changed, new_corpus.changed) == (True, False, True, True)
        assert len(store.sections([s.section_id for s in sections])) == 3


def test_the_service_role_cannot_read_the_ledger(postgres, service_url: str) -> None:
    import psycopg

    from tests.conftest import database_url

    with psycopg.connect(database_url(postgres, "disputes", "disputes"), autocommit=True) as admin:
        admin.execute("CREATE SCHEMA IF NOT EXISTS ledger")
        admin.execute("CREATE TABLE IF NOT EXISTS ledger.cards (card_id TEXT PRIMARY KEY)")
    with psycopg.connect(service_url) as conn, pytest.raises(psycopg.errors.InsufficientPrivilege):
        conn.execute("SELECT 1 FROM ledger.cards").fetchall()


async def test_metrics_count_tool_calls_by_tool_and_outcome(mcp_url: str, base_url: str) -> None:
    import re

    import httpx

    def count(body: str, tool: str, outcome: str) -> float:
        m = re.search(rf'^policy_tool_calls_total\{{outcome="{outcome}",tool="{tool}"\}} (\S+)$', body, re.M)
        return float(m.group(1)) if m else 0.0

    before = httpx.get(f"{base_url}/metrics").text
    await _ok(mcp_url, "get_policy_section", {"section_id": "time-limits"})
    await _call(mcp_url, "search_policy", {"query": ""})  # invalid input: a tool-level error

    response = httpx.get(f"{base_url}/metrics")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    body = response.text
    assert count(body, "get_policy_section", "ok") == count(before, "get_policy_section", "ok") + 1
    assert count(body, "search_policy", "error") == count(before, "search_policy", "error") + 1
    assert "policy_tool_seconds_bucket{" in body
    # The runtime metrics; the process collector (process_cpu_seconds_total) reads /proc and exists on Linux only.
    assert "python_gc_objects_collected_total" in body
