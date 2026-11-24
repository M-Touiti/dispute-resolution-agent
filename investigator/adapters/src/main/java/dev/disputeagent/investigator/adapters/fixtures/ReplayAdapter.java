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
 * does not need a re-recording. With a {@link FaultSwitch}, calls fail as {@link Kind#OVERLOADED} while it is on (task
 * 2.14).
 */
public final class ReplayAdapter implements LlmPort {

    private final Fixtures fixtures;
    private final PriceTable prices;
    private final FaultSwitch faults;

    public ReplayAdapter(Fixtures fixtures, PriceTable prices) {
        this(fixtures, prices, null);
    }

    /** @param faults failure injection, or null for none */
    public ReplayAdapter(Fixtures fixtures, PriceTable prices, FaultSwitch faults) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.faults = faults;
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        if (faults != null) {
            faults.overloadedUntil().ifPresent(until -> {
                throw new LlmCallException(Kind.OVERLOADED, "fault injected: overloaded until " + until, null);
            });
        }
        String key = Fixtures.key(request);
        Fixtures.Recorded recorded = fixtures.read(request).orElseThrow(() -> new LlmCallException(Kind.FIXTURE_MISSING,
                "no fixture " + fixtures.path(key) + " for this request; the prompt, a tool, the model "
                        + "configuration or a tool result changed: re-record (ADR 0006)", null));
        return new LlmResponse(recorded.id(), recorded.model(), recorded.stopReason(), recorded.content(),
                recorded.usage(), recorded.refusalCategory(), prices.cost(recorded.model(), recorded.usage()));
    }
}
