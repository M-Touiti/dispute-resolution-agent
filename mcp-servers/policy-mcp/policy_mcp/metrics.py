"""Prometheus metrics for policy-mcp (ADR 0008), scraped at ``/metrics``.

Per tool call: a counter and a latency histogram by ``tool`` and ``outcome`` (``ok``, ``error`` for a tool-level error
such as invalid input or an unknown section, ``unavailable`` when the store could not be read), the same outcomes as
the investigator's tool spans. The default registry adds the process and Python runtime metrics.
"""

from time import perf_counter

from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

TOOL_CALLS = Counter("policy_tool_calls", "Tool calls answered", ["tool", "outcome"])
TOOL_SECONDS = Histogram(
    "policy_tool_seconds",
    "How long a tool call took",
    ["tool", "outcome"],
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
)


class ToolTimer:
    """Measures one call; ``outcome`` is ``ok`` unless set before the block ends."""

    def __init__(self, tool: str) -> None:
        self.tool = tool
        self.outcome = "ok"

    def __enter__(self) -> "ToolTimer":
        self._start = perf_counter()
        return self

    def __exit__(self, *exc: object) -> None:
        outcome = self.outcome if exc[0] is None else "unavailable"
        TOOL_CALLS.labels(self.tool, outcome).inc()
        TOOL_SECONDS.labels(self.tool, outcome).observe(perf_counter() - self._start)


def exposition() -> tuple[bytes, str]:
    """The scrape body and its content type."""
    return generate_latest(), CONTENT_TYPE_LATEST
