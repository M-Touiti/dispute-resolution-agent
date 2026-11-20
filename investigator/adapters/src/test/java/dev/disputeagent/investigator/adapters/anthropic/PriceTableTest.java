package dev.disputeagent.investigator.adapters.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.Usage;
import org.junit.jupiter.api.Test;

class PriceTableTest {

    private final PriceTable prices = TestPrices.TABLE;

    @Test
    void billsEachKindOfTokenAtItsOwnPrice() {
        // Opus 5.5: $4 in, $20 out, $0.20 cache read, $5 cache write per million tokens.
        LlmCost cost = prices.cost("claude-opus-5-5", new Usage(1_000_000, 100_000, 2_000_000, 10_000));

        assertThat(cost.microUsd()).isEqualTo(4_000_000 + 2_000_000 + 400_000 + 50_000);
        assertThat(cost.priceTableVersion()).isEqualTo("2026-11-20");
    }

    @Test
    void subCentPricesStayExact() {
        // Haiku 5.5: $0.10 per million input tokens; 1,500 tokens = 150 micro-dollars... in nano: 150,000.
        LlmCost cost = prices.cost("claude-haiku-5-5", new Usage(1_500, 40, 0, 0));

        assertThat(cost.microUsd()).isEqualTo(150 + 20);
    }

    @Test
    void anUnpricedModelIsMarkedNotGuessed() {
        LlmCost cost = prices.cost("claude-opus-4-8", new Usage(100, 100, 0, 0));

        assertThat(cost.microUsd()).isZero();
        assertThat(cost.priceTableVersion()).isEqualTo("2026-11-20/unpriced:claude-opus-4-8");
    }
}
