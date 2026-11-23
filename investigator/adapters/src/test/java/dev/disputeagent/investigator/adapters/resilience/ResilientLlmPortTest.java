package dev.disputeagent.investigator.adapters.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind;
import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.Usage;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The decorator with a scripted port and a clock the test moves: no real model, no real waiting for the breaker. */
class ResilientLlmPortTest {

    static final LlmRequest REQUEST = null; // the fake port never looks at it
    static final LlmResponse OK = new LlmResponse("msg", "claude-opus-5-5", StopReason.END_TURN, List.of(),
            new Usage(1, 1, 0, 0), null, new LlmCost(0, "test"));

    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final MovableClock clock = new MovableClock(Instant.parse("2026-11-24T09:00:00Z"));
    final ScriptedPort port = new ScriptedPort();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    static ResilientLlmPort.Settings settings(int attempts, Duration timeout, int concurrent) {
        return new ResilientLlmPort.Settings(timeout, attempts, Duration.ofMillis(1), 2.0, 0.5, Duration.ofMillis(5),
                concurrent, Duration.ofMillis(20), 4, 4, 50f, Duration.ofSeconds(30), 1);
    }

    ResilientLlmPort resilient(ResilientLlmPort.Settings s) {
        return new ResilientLlmPort(port, s, clock, executor);
    }

    static LlmCallException failure(Kind kind) {
        return new LlmCallException(kind, "scripted", null);
    }

    static final class ScriptedPort implements LlmPort {
        final Deque<Supplier<LlmResponse>> script = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        Supplier<LlmResponse> otherwise = () -> OK;

        ScriptedPort then(Supplier<LlmResponse> step) {
            script.add(step);
            return this;
        }

        ScriptedPort thenFail(Kind kind) {
            return then(() -> {
                throw failure(kind);
            });
        }

        @Override
        public LlmResponse send(LlmRequest request) {
            calls.incrementAndGet();
            Supplier<LlmResponse> step = script.poll();
            return (step == null ? otherwise : step).get();
        }
    }

    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        synchronized void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void transientFailuresAreRetriedUntilTheModelAnswers() {
        port.thenFail(Kind.OVERLOADED).thenFail(Kind.RATE_LIMITED);

        assertThat(resilient(settings(3, Duration.ofSeconds(5), 4)).send(REQUEST)).isSameAs(OK);
        assertThat(port.calls).hasValue(3);
    }

    @Test
    void afterTheLastAttemptTheTransientKindIsWhatTheCallerSees() {
        port.otherwise = () -> {
            throw failure(Kind.SERVER_ERROR);
        };

        assertThatThrownBy(() -> resilient(settings(3, Duration.ofSeconds(5), 4)).send(REQUEST))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR));
        assertThat(port.calls).hasValue(3);
    }

    @Test
    void permanentFailuresAreNeitherRetriedNorCountedAgainstTheBreaker() {
        var resilient = resilient(settings(3, Duration.ofSeconds(5), 4));
        port.otherwise = () -> {
            throw failure(Kind.FIXTURE_MISSING);
        };

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> resilient.send(REQUEST)).isInstanceOfSatisfying(LlmCallException.class,
                    e -> assertThat(e.kind()).isEqualTo(Kind.FIXTURE_MISSING));
        }
        assertThat(port.calls).as("one call each, no retry").hasValue(10);
        assertThat(resilient.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theBreakerOpensHoldsTheModelOffThenLetsItBackIn() {
        var resilient = resilient(settings(1, Duration.ofSeconds(5), 4));
        port.otherwise = () -> {
            throw failure(Kind.CONNECTION);
        };
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> resilient.send(REQUEST)).isInstanceOf(LlmCallException.class);
        }
        assertThat(resilient.breakerState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = port.calls.get();
        assertThatThrownBy(() -> resilient.send(REQUEST)).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE);
            assertThat(e.kind().retryable()).as("the case is parked, not escalated").isTrue();
        });
        assertThat(port.calls).as("the model is not called while open").hasValue(before);

        clock.advance(Duration.ofSeconds(31));
        port.otherwise = () -> OK;
        assertThat(resilient.send(REQUEST)).isSameAs(OK);
        assertThat(resilient.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anOpenBreakerIsNotRetriedHere() {
        var resilient = resilient(settings(5, Duration.ofSeconds(5), 4));
        port.otherwise = () -> {
            throw failure(Kind.OVERLOADED);
        };
        assertThatThrownBy(() -> resilient.send(REQUEST)).isInstanceOf(LlmCallException.class); // 4 attempts open it
        int before = port.calls.get();

        assertThatThrownBy(() -> resilient.send(REQUEST)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        assertThat(port.calls).hasValue(before);
    }

    @Test
    void anAttemptThatTakesTooLongIsCancelledAndCountsAsATimeout() {
        AtomicBoolean interrupted = new AtomicBoolean();
        port.then(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return OK;
        });

        long start = System.nanoTime();
        assertThatThrownBy(() -> resilient(settings(1, Duration.ofMillis(100), 4)).send(REQUEST))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!interrupted.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(interrupted).as("the cancelled attempt was interrupted").isTrue();
    }

    @Test
    void aFullBulkheadIsUnavailableAndDoesNotTripTheBreaker() throws Exception {
        var resilient = resilient(settings(1, Duration.ofSeconds(5), 1));
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        port.then(() -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OK;
        });
        var first = CompletableFuture.supplyAsync(() -> resilient.send(REQUEST), executor);
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> resilient.send(REQUEST)).isInstanceOfSatisfying(LlmCallException.class,
                    e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        }
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(OK);
        assertThat(resilient.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
