package dev.disputeagent.investigator.application.replay;

import java.time.Duration;
import java.util.Objects;

/**
 * When a parked case is replayed, and when it stops being (ADR 0008).
 *
 * @param maxReplays after this many replays a case that parks again escalates as {@code DEPENDENCY_UNAVAILABLE}
 * @param firstDelay how long a case waits after it first parks; each later wait doubles, up to {@code maxDelay}
 * @param inFlight   how long a case is held after its replay is sent, so the next sweep does not send it again
 */
public record ReplayPolicy(int maxReplays, Duration firstDelay, Duration maxDelay, Duration inFlight) {

    public ReplayPolicy {
        Objects.requireNonNull(firstDelay, "firstDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(inFlight, "inFlight");
        if (maxReplays < 0) {
            throw new IllegalArgumentException("maxReplays must not be negative");
        }
    }

    /** The wait before the next replay of a case replayed {@code replays} times so far. */
    public Duration delayAfter(int replays) {
        Duration delay = firstDelay;
        for (int i = 0; i < replays && delay.compareTo(maxDelay) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
