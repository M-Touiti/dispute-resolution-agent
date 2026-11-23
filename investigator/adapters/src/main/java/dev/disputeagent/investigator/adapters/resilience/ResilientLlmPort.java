package dev.disputeagent.investigator.adapters.resilience;

import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Resilience4j around the model port (ADR 0008), outermost to innermost: retry, circuit breaker, bulkhead, time
 * limiter. Each attempt passes the breaker and the bulkhead and is timed on its own. Resilience4j's types never leave
 * this class: every failure is an {@link LlmCallException} with a provider-neutral kind.
 *
 * <ul>
 *   <li><b>Transient kinds</b> ({@code RATE_LIMITED}, {@code OVERLOADED}, {@code SERVER_ERROR}, {@code TIMEOUT},
 *       {@code CONNECTION}) are retried with exponential backoff and jitter, and count against the breaker.</li>
 *   <li><b>Permanent kinds</b> ({@code BAD_REQUEST}, {@code AUTHENTICATION}, {@code FIXTURE_MISSING}, {@code OTHER})
 *       are neither retried nor counted: the provider answered, the request is what is wrong.</li>
 *   <li>An open breaker or a full bulkhead is {@code UNAVAILABLE}: retryable for the case (it is parked and
 *       replayed), not retried here, and a full bulkhead does not count against the breaker.</li>
 *   <li>An attempt that outlives its time limit is cancelled (its thread interrupted) and counts as {@code TIMEOUT}.</li>
 * </ul>
 *
 * The SDK's own retries are 0 when this class is in front of it: retries live in one place.
 */
public final class ResilientLlmPort implements LlmPort {

    /** The knobs, from configuration ({@code investigator.resilience}). */
    public record Settings(Duration attemptTimeout, int maxAttempts, Duration firstBackoff, double backoffMultiplier,
            double jitter, Duration maxBackoff, int maxConcurrentCalls, Duration bulkheadWait, int breakerWindow,
            int breakerMinimumCalls, float breakerFailureRate, Duration breakerOpenFor, int breakerHalfOpenCalls) {

        public Settings {
            Objects.requireNonNull(attemptTimeout, "attemptTimeout");
            if (maxAttempts < 1 || maxConcurrentCalls < 1) {
                throw new IllegalArgumentException("maxAttempts and maxConcurrentCalls must be positive");
            }
        }
    }

    static final Set<Kind> TRANSIENT = EnumSet.of(Kind.RATE_LIMITED, Kind.OVERLOADED, Kind.SERVER_ERROR, Kind.TIMEOUT,
            Kind.CONNECTION);

    private static final Predicate<Throwable> IS_TRANSIENT =
            e -> e instanceof LlmCallException l && TRANSIENT.contains(l.kind());

    private final LlmPort delegate;
    private final ExecutorService attempts;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;

    /**
     * @param clock    the breaker's clock (how long it stays open); a test moves it instead of waiting
     * @param attempts runs each attempt, so the time limiter can cancel it; virtual threads in production
     */
    public ResilientLlmPort(LlmPort delegate, Settings s, Clock clock, ExecutorService attempts) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.retry = Retry.of("llm", RetryConfig.custom()
                .maxAttempts(s.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(s.firstBackoff(), s.backoffMultiplier(),
                        s.jitter(), s.maxBackoff()))
                .retryOnException(IS_TRANSIENT)
                .failAfterMaxAttempts(false)
                .build());
        this.breaker = CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .slidingWindowSize(s.breakerWindow())
                .minimumNumberOfCalls(s.breakerMinimumCalls())
                .failureRateThreshold(s.breakerFailureRate())
                .waitDurationInOpenState(s.breakerOpenFor())
                .permittedNumberOfCallsInHalfOpenState(s.breakerHalfOpenCalls())
                .recordException(IS_TRANSIENT)
                .ignoreException(IS_TRANSIENT.negate())
                .clock(clock)
                .build());
        this.bulkhead = Bulkhead.of("llm", BulkheadConfig.custom()
                .maxConcurrentCalls(s.maxConcurrentCalls())
                .maxWaitDuration(s.bulkheadWait())
                .build());
        this.timeLimiter = TimeLimiter.of("llm", TimeLimiterConfig.custom()
                .timeoutDuration(s.attemptTimeout())
                .cancelRunningFuture(true)
                .build());
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        Callable<LlmResponse> timed = () -> timed(request);
        Callable<LlmResponse> bounded = () -> {
            try {
                return Bulkhead.decorateCallable(bulkhead, timed).call();
            } catch (BulkheadFullException e) {
                throw new LlmCallException(Kind.UNAVAILABLE, "every model slot is busy (bulkhead full)", e);
            }
        };
        Callable<LlmResponse> guarded = () -> {
            try {
                return CircuitBreaker.decorateCallable(breaker, bounded).call();
            } catch (CallNotPermittedException e) {
                throw new LlmCallException(Kind.UNAVAILABLE, "the model's circuit breaker is open", e);
            }
        };
        try {
            return Retry.decorateCallable(retry, guarded).call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCallException(Kind.OTHER, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private LlmResponse timed(LlmRequest request) throws Exception {
        try {
            return timeLimiter.executeFutureSupplier(() -> attempts.submit(() -> delegate.send(request)));
        } catch (TimeoutException e) {
            throw new LlmCallException(Kind.TIMEOUT, "no answer within " + timeLimiter.getTimeLimiterConfig()
                    .getTimeoutDuration() + "; the attempt was cancelled", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** For tests and, later, metrics (task 2.11). */
    public CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    /**
     * False while the breaker holds the model off: the replay sweep waits rather than park cases again.
     *
     * <p>It asks the breaker for a permit and gives it back at once, rather than reading its state. Resilience4j moves
     * an open breaker to half-open only when a call is attempted after the wait; reading the state would leave a breaker
     * open forever when the sweep is the only caller (no new disputes), and parked cases would never be replayed.
     */
    public boolean available() {
        if (breaker.tryAcquirePermission()) {
            breaker.releasePermission();
            return true;
        }
        return false;
    }
}
