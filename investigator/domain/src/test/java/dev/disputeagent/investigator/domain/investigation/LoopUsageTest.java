package dev.disputeagent.investigator.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoopUsageTest {

    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    private static final LoopBudget BUDGET = new LoopBudget(3, 5, 1_000, Duration.ofMinutes(2));

    @Test
    void withinBudgetNothingIsReached() {
        var usage = new LoopUsage(BUDGET, T0);
        usage.modelCallCompleted(100, 50);
        usage.toolCallsCompleted(2);

        assertThat(usage.beforeModelCall(T0.plusSeconds(10))).isEmpty();
        assertThat(usage.beforeToolCalls(3, T0.plusSeconds(10))).isEmpty();
    }

    @Test
    void modelCallCap() {
        var usage = new LoopUsage(BUDGET, T0);
        for (int i = 0; i < 3; i++) {
            usage.modelCallCompleted(10, 10);
        }
        assertThat(usage.beforeModelCall(T0)).hasValueSatisfying(c -> assertThat(c.cap()).isEqualTo("model_calls"));
    }

    @Test
    void tokenCapCountsInputAndOutput() {
        var usage = new LoopUsage(BUDGET, T0);
        usage.modelCallCompleted(600, 400);

        assertThat(usage.tokens()).isEqualTo(1_000);
        assertThat(usage.beforeModelCall(T0)).hasValueSatisfying(c -> assertThat(c.cap()).isEqualTo("tokens"));
    }

    @Test
    void aTurnOfToolCallsMustFitWhole() {
        var usage = new LoopUsage(BUDGET, T0);
        usage.toolCallsCompleted(4);

        assertThat(usage.beforeToolCalls(1, T0)).isEmpty();
        assertThat(usage.beforeToolCalls(2, T0)).hasValueSatisfying(c -> assertThat(c.cap()).isEqualTo("tool_calls"));
    }

    @Test
    void wallClockCap() {
        var usage = new LoopUsage(BUDGET, T0);

        assertThat(usage.beforeModelCall(T0.plus(Duration.ofSeconds(119)))).isEmpty();
        assertThat(usage.beforeModelCall(T0.plus(Duration.ofMinutes(2))))
                .hasValueSatisfying(c -> assertThat(c.cap()).isEqualTo("wall_clock"));
        assertThat(usage.beforeToolCalls(1, T0.plus(Duration.ofMinutes(3)))).isPresent();
    }

    @Test
    void budgetsMustBePositive() {
        assertThatThrownBy(() -> new LoopBudget(0, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoopBudget(1, 1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
