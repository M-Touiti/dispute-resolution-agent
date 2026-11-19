package dev.disputeagent.investigator.domain.investigation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What one investigation has used so far, against its {@link LoopBudget}. The loop asks {@link #beforeModelCall}
 * before every model call and {@link #beforeToolCalls} before running a turn's tool calls; a non-empty answer ends
 * the loop with an escalation.
 */
public final class LoopUsage {

    /** Which cap was reached and by how much, for the audit log. */
    public record CapReached(String cap, String detail) {
    }

    private final LoopBudget budget;
    private final Instant startedAt;
    private int modelCalls;
    private int toolCalls;
    private long tokens;

    public LoopUsage(LoopBudget budget, Instant startedAt) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public Optional<CapReached> beforeModelCall(Instant now) {
        if (modelCalls >= budget.maxModelCalls()) {
            return Optional.of(new CapReached("model_calls", modelCalls + " of " + budget.maxModelCalls()));
        }
        if (tokens >= budget.maxTokens()) {
            return Optional.of(new CapReached("tokens", tokens + " of " + budget.maxTokens()));
        }
        return wallClock(now);
    }

    /** Checked before a turn's calls run: all of them must fit, so a turn is never half executed. */
    public Optional<CapReached> beforeToolCalls(int calls, Instant now) {
        if (toolCalls + calls > budget.maxToolCalls()) {
            return Optional.of(new CapReached("tool_calls",
                    toolCalls + " used, " + calls + " requested, cap " + budget.maxToolCalls()));
        }
        return wallClock(now);
    }

    public void modelCallCompleted(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        modelCalls++;
        tokens += inputTokens + outputTokens;
    }

    public void toolCallsCompleted(int calls) {
        if (calls < 0) {
            throw new IllegalArgumentException("calls must not be negative");
        }
        toolCalls += calls;
    }

    private Optional<CapReached> wallClock(Instant now) {
        Duration elapsed = Duration.between(startedAt, now);
        if (elapsed.compareTo(budget.maxWallClock()) >= 0) {
            return Optional.of(new CapReached("wall_clock", elapsed + " of " + budget.maxWallClock()));
        }
        return Optional.empty();
    }

    public int modelCalls() {
        return modelCalls;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public long tokens() {
        return tokens;
    }
}
