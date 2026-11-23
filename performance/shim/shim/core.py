"""The shim's pure parts: which dispute to send next, and the numbers of a run (task 2.13).

Only the golden disputes have recorded model answers (the replay adapter misses on anything else), so a load test
re-sends the same 30 disputes. A dispute is never in flight twice: it is free again once its ``disputes.decided``
arrived. When all of them are in flight the arrival is rejected and counted, which is how saturation shows.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field


class DisputePool:
    """Round robin over the disputes that are not in flight."""

    def __init__(self, dispute_ids: list[str]) -> None:
        if not dispute_ids:
            raise ValueError("no disputes to send")
        self._ids = list(dispute_ids)
        self._in_flight: set[str] = set()
        self._next = 0

    def acquire(self, skip: frozenset[str] = frozenset()) -> str | None:
        """The next free dispute (not in ``skip``), now in flight; ``None`` when none is free."""
        for _ in range(len(self._ids)):
            candidate = self._ids[self._next]
            self._next = (self._next + 1) % len(self._ids)
            if candidate not in self._in_flight and candidate not in skip:
                self._in_flight.add(candidate)
                return candidate
        return None

    def release(self, dispute_id: str) -> None:
        self._in_flight.discard(dispute_id)

    @property
    def in_flight(self) -> int:
        return len(self._in_flight)

    @property
    def size(self) -> int:
        return len(self._ids)


def percentile(values: list[float], p: float) -> float | None:
    """Nearest-rank percentile (``p`` in 0..100), the definition k6 and most load tools report."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(p / 100 * len(ordered)))
    return ordered[rank - 1]


@dataclass
class Sent:
    dispute_id: str
    sent_at: float  # wall clock, seconds since the epoch
    latency_s: float | None = None  # publish to disputes.decided; None until decided
    state: str | None = None  # DECIDED or ESCALATED, from the decided event


@dataclass
class RunStats:
    """Everything sent and rejected; a summary is taken over a window (``since``)."""

    sent: list[Sent] = field(default_factory=list)
    rejected_at: list[float] = field(default_factory=list)

    def summary(self, since: float) -> dict[str, object]:
        window = [s for s in self.sent if s.sent_at >= since]
        done = [s for s in window if s.latency_s is not None]
        latencies = [s.latency_s for s in done if s.latency_s is not None]
        rejected = sum(1 for t in self.rejected_at if t >= since)
        states: dict[str, int] = {}
        for s in done:
            states[s.state or "UNKNOWN"] = states.get(s.state or "UNKNOWN", 0) + 1
        throughput = None
        if done:
            first = min(s.sent_at for s in window)
            last = max(s.sent_at + (s.latency_s or 0.0) for s in done)
            throughput = len(done) / (last - first) if last > first else None

        def ms(v: float | None) -> float | None:
            return None if v is None else round(v * 1000, 1)

        return {
            "sent": len(window),
            "decided": len(done),
            "not_decided": len(window) - len(done),
            "rejected_all_in_flight": rejected,
            "states": states,
            "cases_per_second": None if throughput is None else round(throughput, 2),
            "e2e_ms": {
                "p50": ms(percentile(latencies, 50)),
                "p95": ms(percentile(latencies, 95)),
                "p99": ms(percentile(latencies, 99)),
                "max": ms(max(latencies) if latencies else None),
            },
        }
