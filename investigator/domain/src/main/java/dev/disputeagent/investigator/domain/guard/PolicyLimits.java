package dev.disputeagent.investigator.domain.guard;

import dev.disputeagent.investigator.domain.decision.Money;
import java.util.Objects;

/**
 * The numbers of the dispute policy that the guard enforces. They must match {@code data/datagen/policy.py} and the
 * policy corpus; the application supplies them from configuration, with {@link #DEFAULT} as the published values.
 */
public record PolicyLimits(int timeLimitDays, Money provisionalCreditLimit, int goodsNotReceivedWaitDays) {

    /**
     * 120 days from posting to dispute and provisional credit up to 3,000.00 EUR (data/policy-corpus/general.md);
     * 15 days' wait after the expected delivery date (goods-not-received.md).
     */
    public static final PolicyLimits DEFAULT = new PolicyLimits(120, Money.eur(300_000), 15);

    public PolicyLimits {
        Objects.requireNonNull(provisionalCreditLimit, "provisionalCreditLimit");
        if (timeLimitDays <= 0) {
            throw new IllegalArgumentException("timeLimitDays must be positive");
        }
        if (goodsNotReceivedWaitDays < 0) {
            throw new IllegalArgumentException("goodsNotReceivedWaitDays must not be negative");
        }
    }
}
