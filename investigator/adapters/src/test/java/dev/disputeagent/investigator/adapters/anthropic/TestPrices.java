package dev.disputeagent.investigator.adapters.anthropic;

import java.util.Map;

/** The same prices as {@code investigator.llm.prices} in the bootstrap's application.yml, for adapter tests. */
final class TestPrices {

    static final PriceTable TABLE = new PriceTable("2026-11-20", Map.of(
            "claude-opus-5-5", PriceTable.ModelPrice.perMillion(4.00, 20.00, 0.20, 5.00),
            "claude-haiku-5-5", PriceTable.ModelPrice.perMillion(0.10, 0.50, 0.01, 0.125)));

    private TestPrices() {
    }
}
