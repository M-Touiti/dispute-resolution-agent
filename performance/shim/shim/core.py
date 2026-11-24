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
    reason: str | None = None  # the escalation reason, when escalated

    @property
    def outcome(self) -> str:
        return f"{self.state or 'UNKNOWN'} {self.reason}" if self.reason else self.state or "UNKNOWN"


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
        by_dispute: dict[str, set[str]] = {}
        for s in done:
            states[s.outcome] = states.get(s.outcome, 0) + 1
            by_dispute.setdefault(s.dispute_id, set()).add(s.outcome)
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
            # Replayed answers are the same every time: a dispute with two outcomes was changed by the load or a fault.
            "outcome_varied": {d: sorted(o) for d, o in sorted(by_dispute.items()) if len(o) > 1},
            "cases_per_second": None if throughput is None else round(throughput, 2),
            "e2e_ms": {
                "p50": ms(percentile(latencies, 50)),
                "p95": ms(percentile(latencies, 95)),
                "p99": ms(percentile(latencies, 99)),
                "max": ms(max(latencies) if latencies else None),
            },
        }


def phases(sent: list[Sent], since: float, fault_start: float, fault_end: float) -> dict[str, dict[str, object]]:
    """The run cut at the fault (task 2.14): disputes sent before it, during it and after it, with their outcomes."""
    cuts = {"before": (since, fault_start), "during": (fault_start, fault_end), "after": (fault_end, float("inf"))}
    result: dict[str, dict[str, object]] = {}
    for name, (start, end) in cuts.items():
        window = [s for s in sent if start <= s.sent_at < end]
        latencies = [s.latency_s for s in window if s.latency_s is not None]
        outcomes: dict[str, int] = {}
        for s in window:
            if s.latency_s is not None:
                outcomes[s.outcome] = outcomes.get(s.outcome, 0) + 1
        result[name] = {
            "sent": len(window),
            "decided": len(latencies),
            "outcomes": dict(sorted(outcomes.items())),
            "e2e_p50_ms": _ms(percentile(latencies, 50)),
            "e2e_p99_ms": _ms(percentile(latencies, 99)),
            "e2e_max_ms": _ms(max(latencies) if latencies else None),
        }
    return result


def recovered_after(sent: list[Sent], fault_end: float) -> float | None:
    """Seconds from the fault's end until every dispute sent before it was decided; None while one is still open."""
    before = [s for s in sent if s.sent_at < fault_end]
    if not before or any(s.latency_s is None for s in before):
        return None
    last = max(s.sent_at + (s.latency_s or 0.0) for s in before)
    return round(max(0.0, last - fault_end), 1)


def _ms(v: float | None) -> float | None:
    return None if v is None else round(v * 1000, 1)


def zero_after(samples: list[tuple[float, int]], fault_end: float) -> float | None:
    """Seconds after the fault's end until the sampled count was zero for good; None if it never was."""
    if not samples or samples[-1][1] != 0:
        return None
    last_nonzero = max((t for t, n in samples if n > 0), default=None)
    if last_nonzero is None or last_nonzero <= fault_end:
        return 0.0
    return round(last_nonzero - fault_end, 1)
