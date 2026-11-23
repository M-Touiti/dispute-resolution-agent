import pytest

from shim.core import DisputePool, RunStats, Sent, percentile


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
