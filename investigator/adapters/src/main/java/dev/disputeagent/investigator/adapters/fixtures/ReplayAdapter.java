package dev.disputeagent.investigator.adapters.fixtures;

import dev.disputeagent.investigator.adapters.anthropic.PriceTable;
import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import java.util.Objects;

/**
 * Serves recorded fixtures (ADR 0006): the only adapter in pull-request CI and in the load test. It has no client and
 * no key, so it cannot call the API: a request without a fixture fails with {@link Kind#FIXTURE_MISSING}, naming the
 * key and the file, and the case escalates. The cost is recomputed from the current price table, so a price change
 * does not need a re-recording.
 */
public final class ReplayAdapter implements LlmPort {

    private final Fixtures fixtures;
    private final PriceTable prices;

    public ReplayAdapter(Fixtures fixtures, PriceTable prices) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        String key = Fixtures.key(request);
        Fixtures.Recorded recorded = fixtures.read(request).orElseThrow(() -> new LlmCallException(Kind.FIXTURE_MISSING,
                "no fixture " + fixtures.path(key) + " for this request; the prompt, a tool, the model "
                        + "configuration or a tool result changed: re-record (ADR 0006)", null));
        return new LlmResponse(recorded.id(), recorded.model(), recorded.stopReason(), recorded.content(),
                recorded.usage(), recorded.refusalCategory(), prices.cost(recorded.model(), recorded.usage()));
    }
}
