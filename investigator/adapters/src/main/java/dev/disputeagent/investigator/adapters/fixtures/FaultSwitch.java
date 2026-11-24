package dev.disputeagent.investigator.adapters.fixtures;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Failure injection for the replay adapter (task 2.14): while switched on, every model call fails as
 * {@code OVERLOADED}, the answer the API gives when it is overloaded, so the retries, the circuit breaker, parking and
 * replay can be watched under load without spending anything. It exists only with the replay adapter and only when
 * {@code investigator.faults.enabled} is set; the live adapters never see it.
 */
public final class FaultSwitch {

    private final Clock clock;
    private volatile Instant overloadedUntil = Instant.MIN;

    public FaultSwitch(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Model calls fail as overloaded for {@code duration} from now; returns when it ends. */
    public Instant overloadFor(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive, got " + duration);
        }
        Instant until = clock.instant().plus(duration);
        overloadedUntil = until;
        return until;
    }

    public void clear() {
        overloadedUntil = Instant.MIN;
    }

    /** When the overload ends, while it lasts. */
    public Optional<Instant> overloadedUntil() {
        Instant until = overloadedUntil;
        return clock.instant().isBefore(until) ? Optional.of(until) : Optional.empty();
    }
}
