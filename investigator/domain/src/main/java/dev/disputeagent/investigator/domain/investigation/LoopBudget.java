package dev.disputeagent.investigator.domain.investigation;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard caps on one investigation. Reaching any of them escalates the case with {@link EscalationReason#CAP_REACHED};
 * the loop never returns a truncated decision. Values come from configuration.
 *
 * @param maxTokens input plus output tokens over all model calls of the case
 */
public record LoopBudget(int maxModelCalls, int maxToolCalls, long maxTokens, Duration maxWallClock) {

    public LoopBudget {
        Objects.requireNonNull(maxWallClock, "maxWallClock");
        if (maxModelCalls <= 0 || maxToolCalls <= 0 || maxTokens <= 0 || maxWallClock.isNegative()
                || maxWallClock.isZero()) {
            throw new IllegalArgumentException("every cap must be positive");
        }
    }
}
