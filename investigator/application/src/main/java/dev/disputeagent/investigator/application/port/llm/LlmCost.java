package dev.disputeagent.investigator.application.port.llm;

import java.util.Objects;

/**
 * What one call cost, computed by the adapter from the usage and a versioned price table in configuration (ADR 0001).
 * Integer micro-dollars, so sums are exact. The version is recorded with the cost: a price change is visible.
 */
public record LlmCost(long microUsd, String priceTableVersion) {

    public LlmCost {
        Objects.requireNonNull(priceTableVersion, "priceTableVersion");
        if (microUsd < 0) {
            throw new IllegalArgumentException("cost must not be negative");
        }
    }

    @Override
    public String toString() {
        return String.format("$%d.%06d (%s)", microUsd / 1_000_000, microUsd % 1_000_000, priceTableVersion);
    }
}
