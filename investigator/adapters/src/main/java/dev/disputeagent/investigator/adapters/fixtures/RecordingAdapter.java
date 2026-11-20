package dev.disputeagent.investigator.adapters.fixtures;

import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import java.util.Objects;

/**
 * Wraps the live adapter and writes every request and response as a fixture (ADR 0006). Only the recording session
 * uses it; it spends money. A failed call writes nothing: the exception propagates as from the live adapter.
 */
public final class RecordingAdapter implements LlmPort {

    private final LlmPort live;
    private final Fixtures fixtures;

    public RecordingAdapter(LlmPort live, Fixtures fixtures) {
        this.live = Objects.requireNonNull(live, "live");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        LlmResponse response = live.send(request);
        fixtures.write(request, response);
        return response;
    }
}
