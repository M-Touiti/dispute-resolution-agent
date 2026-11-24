import pytest

from shim.core import DisputePool, RunStats, Sent, percentile, phases, recovered_after, zero_after


def test_pool_goes_round_robin_and_never_hands_out_a_dispute_in_flight() -> None:
    pool = DisputePool(["A", "B", "C"])
    assert [pool.acquire(), pool.acquire()] == ["A", "B"]
    pool.release("A")
    assert pool.acquire() == "C"
    assert pool.acquire() == "A"
    assert pool.acquire() is None
    assert pool.in_flight == 3


def test_pool_skips_the_disputes_it_is_told_to() -> None:
    pool = DisputePool(["A", "B"])
    assert pool.acquire(frozenset({"A"})) == "B"
    assert pool.acquire(frozenset({"A"})) is None


def test_an_empty_pool_is_refused() -> None:
    with pytest.raises(ValueError):
        DisputePool([])


def test_percentile_is_nearest_rank() -> None:
    values = [float(v) for v in range(1, 101)]
    assert percentile(values, 50) == 50
    assert percentile(values, 99) == 99
    assert percentile(values, 100) == 100
    assert percentile([7.0], 99) == 7.0
    assert percentile([], 50) is None


def test_summary_counts_the_window_only() -> None:
    stats = RunStats(
        sent=[
            Sent("OLD", 90.0, 1.0, "DECIDED"),
            Sent("A", 100.0, 0.5, "DECIDED"),
            Sent("B", 101.0, 1.5, "ESCALATED"),
            Sent("C", 102.0),  # not decided yet
        ],
        rejected_at=[95.0, 103.0],
    )
    s = stats.summary(since=100.0)
    assert (s["sent"], s["decided"], s["not_decided"], s["rejected_all_in_flight"]) == (3, 2, 1, 1)
    assert s["states"] == {"DECIDED": 1, "ESCALATED": 1}
    # Two decisions between the first send (100.0) and the last decision (102.5).
    assert s["cases_per_second"] == 0.8
    assert s["e2e_ms"] == {"p50": 500.0, "p95": 1500.0, "p99": 1500.0, "max": 1500.0}


def test_summary_of_an_empty_window() -> None:
    s = RunStats().summary(since=0.0)
    assert s["decided"] == 0
    assert s["cases_per_second"] is None
    assert s["e2e_ms"]["p99"] is None


def test_an_escalation_counts_with_its_reason() -> None:
    stats = RunStats(sent=[Sent("A", 1.0, 0.1, "ESCALATED", "DEPENDENCY_UNAVAILABLE"), Sent("B", 1.0, 0.1, "DECIDED")])
    assert stats.summary(since=0.0)["states"] == {"ESCALATED DEPENDENCY_UNAVAILABLE": 1, "DECIDED": 1}


def test_phases_cut_the_run_at_the_fault() -> None:
    sent = [
        Sent("A", 10.0, 0.2, "DECIDED"),
        Sent("B", 30.0, 50.0, "DECIDED"),  # parked during the fault, replayed after it
        Sent("C", 35.0, 0.3, "ESCALATED", "DEPENDENCY_UNAVAILABLE"),
        Sent("D", 80.0, 0.2, "DECIDED"),
        Sent("E", 81.0),  # still open
    ]
    p = phases(sent, since=0.0, fault_start=20.0, fault_end=60.0)
    assert (p["before"]["sent"], p["during"]["sent"], p["after"]["sent"]) == (1, 2, 2)
    assert p["during"]["outcomes"] == {"DECIDED": 1, "ESCALATED DEPENDENCY_UNAVAILABLE": 1}
    assert p["during"]["e2e_max_ms"] == 50000.0
    assert p["after"]["decided"] == 1
    # B, sent during the fault, was the last of those decided: at 80.0, 20 s after the fault ended.
    assert recovered_after(sent, fault_end=60.0) == 20.0
    assert recovered_after([*sent, Sent("F", 59.0)], fault_end=60.0) is None


def test_zero_after_is_when_the_count_settled_at_zero() -> None:
    samples = [(10.0, 0), (30.0, 4), (70.0, 2), (75.0, 0), (80.0, 0)]
    assert zero_after(samples, fault_end=60.0) == 10.0
    assert zero_after(samples[:3], fault_end=60.0) is None  # still parked at the end
    assert zero_after([(10.0, 0), (80.0, 0)], fault_end=60.0) == 0.0


def test_a_dispute_with_two_outcomes_is_named() -> None:
    stats = RunStats(
        sent=[
            Sent("A", 1.0, 0.1, "DECIDED"),
            Sent("A", 2.0, 0.1, "ESCALATED", "DEPENDENCY_UNAVAILABLE"),
            Sent("B", 1.0, 0.1, "DECIDED"),
            Sent("B", 2.0, 0.1, "DECIDED"),
        ]
    )
    assert stats.summary(since=0.0)["outcome_varied"] == {"A": ["DECIDED", "ESCALATED DEPENDENCY_UNAVAILABLE"]}
