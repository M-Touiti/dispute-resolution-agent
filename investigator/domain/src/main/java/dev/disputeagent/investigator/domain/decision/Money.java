package dev.disputeagent.investigator.domain.decision;

import java.util.Objects;

/** An amount in minor units (cents) of one currency. The scope is single-currency EUR (ADR 0001). */
public record Money(long amountMinor, String currency) implements Comparable<Money> {

    public static final String EUR = "EUR";

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amountMinor);
        }
        if (!EUR.equals(currency)) {
            throw new IllegalArgumentException("only EUR is in scope, got " + currency);
        }
    }

    public static Money eur(long amountMinor) {
        return new Money(amountMinor, EUR);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(amountMinor, other.amountMinor);
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return String.format("%d.%02d %s", amountMinor / 100, amountMinor % 100, currency);
    }
}
