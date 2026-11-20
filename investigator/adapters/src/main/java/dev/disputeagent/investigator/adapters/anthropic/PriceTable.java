package dev.disputeagent.investigator.adapters.anthropic;

import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * Prices per model, versioned, from configuration ({@code investigator.llm.prices}, ADR 0001). Prices are integer
 * nano-dollars per token (dollars per million tokens times 1,000), so a $0.10 per million price is exact; costs are
 * rounded to micro-dollars once per call.
 *
 * <p>{@code input_tokens} in the API's usage excludes cached tokens, so the four counts are billed separately. A
 * model missing from the table (for example a server-side fallback) costs 0 and is marked {@code unpriced:<model>} in
 * the version, so the gap shows in the audit rather than silently pricing it as another model.
 */
public record PriceTable(String version, Map<String, ModelPrice> models) {

    /** Nano-dollars per token for each kind of token. */
    public record ModelPrice(long input, long output, long cacheRead, long cacheWrite) {
        public ModelPrice {
            if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0) {
                throw new IllegalArgumentException("prices must not be negative");
            }
        }

        /** From dollars per million tokens, as price lists publish them. */
        public static ModelPrice perMillion(double input, double output, double cacheRead, double cacheWrite) {
            return new ModelPrice(nano(input), nano(output), nano(cacheRead), nano(cacheWrite));
        }

        private static long nano(double dollarsPerMillion) {
            return Math.round(dollarsPerMillion * 1_000);
        }
    }

    public PriceTable {
        Objects.requireNonNull(version, "version");
        models = Map.copyOf(models);
    }

    public LlmCost cost(String model, Usage usage) {
        ModelPrice price = models.get(model);
        if (price == null) {
            return new LlmCost(0, version + "/unpriced:" + model);
        }
        long nano = usage.inputTokens() * price.input()
                + usage.outputTokens() * price.output()
                + usage.cacheReadInputTokens() * price.cacheRead()
                + usage.cacheCreationInputTokens() * price.cacheWrite();
        return new LlmCost((nano + 500) / 1_000, version);
    }
}
