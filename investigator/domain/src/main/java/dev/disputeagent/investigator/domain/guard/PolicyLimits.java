package dev.disputeagent.investigator.domain.guard;

import dev.disputeagent.investigator.domain.decision.Money;
import java.util.Objects;

/**
 * The numbers of the dispute policy that the guard enforces. They must match {@code data/datagen/policy.py} and the
 * policy corpus; the application supplies them from configuration, with {@link #DEFAULT} as the published values.
 */
public record PolicyLimits(int timeLimitDays, Money provisionalCreditLimit) {

    /** 120 days from posting to dispute; provisional credit up to 3,000.00 EUR (data/policy-corpus/general.md). */
    public static final PolicyLimits DEFAULT = new PolicyLimits(120, Money.eur(300_000));

    public PolicyLimits {
        Objects.requireNonNull(provisionalCreditLimit, "provisionalCreditLimit");
        if (timeLimitDays <= 0) {
            throw new IllegalArgumentException("timeLimitDays must be positive");
        }
    }
}
