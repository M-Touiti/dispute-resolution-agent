package dev.disputeagent.investigator.adapters.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.adapters.anthropic.PriceTable;
import dev.disputeagent.investigator.adapters.web.FaultController;
import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** Failure injection (task 2.14): the replay adapter answers overloaded while the switch is on, and only then. */
class FaultSwitchTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-23T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final FaultSwitch faults = new FaultSwitch(clock);

    @Test
    void theSwitchLastsItsDurationAndCanBeCleared() {
        assertThat(faults.overloadedUntil()).isEmpty();
        Instant until = faults.overloadFor(Duration.ofSeconds(40));
        assertThat(until).isEqualTo(clock.now.plusSeconds(40));
        clock.now = clock.now.plusSeconds(39);
        assertThat(faults.overloadedUntil()).contains(until);
        clock.now = clock.now.plusSeconds(1);
        assertThat(faults.overloadedUntil()).isEmpty();

        faults.overloadFor(Duration.ofSeconds(40));
        faults.clear();
        assertThat(faults.overloadedUntil()).isEmpty();
        assertThatThrownBy(() -> faults.overloadFor(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replayAnswersOverloadedWhileOnAndReadsFixturesOtherwise(@TempDir Path dir) {
        var replay = new ReplayAdapter(new Fixtures(dir), new PriceTable("test", Map.of()), faults);
        LlmRequest request = request();

        faults.overloadFor(Duration.ofSeconds(40));
        assertThatThrownBy(() -> replay.send(request)).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.OVERLOADED);
            assertThat(e.kind().retryable()).isTrue();
        });

        // Off again: the request goes to the fixtures (none here), as without a switch.
        clock.now = clock.now.plusSeconds(40);
        assertThatThrownBy(() -> replay.send(request)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.FIXTURE_MISSING));
    }

    @Test
    void theEndpointBoundsTheDuration() {
        var controller = new FaultController(faults);
        assertThat(controller.overload(0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.overload(601).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(faults.overloadedUntil()).isEmpty();

        var on = controller.overload(40);
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody()).isEqualTo("{\"llm_overloaded_until\":\"2026-11-23T10:00:40Z\"}");
        assertThat(controller.state().getBody()).isEqualTo(on.getBody());
        assertThat(controller.clear().getBody()).isEqualTo("{\"llm_overloaded_until\":null}");
        assertThat(faults.overloadedUntil()).isEmpty();
    }

    private static LlmRequest request() {
        return new LlmRequest("claude-haiku-5-5", Effort.LOW, 1_000, List.of(new SystemBlock("Triage.", true)),
                List.of(LlmMessage.userText("Triage DSP-000001.")), List.of(), null);
    }
}
