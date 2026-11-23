package dev.disputeagent.investigator.adapters.otel;

import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * A span and a latency timer per model attempt (ADR 0008). Sits inside the resilience decorator, so a retried call
 * shows one span per attempt, each a child of the case's span.
 *
 * <p>Low-cardinality tags, also on the timer: {@code model}, {@code effort}, {@code stop_reason}, {@code kind} (the
 * failure kind, {@code none} on success). Every tag is always present, so the timer has one tag set. On the span only:
 * the answering model, the input, output, cache read and cache creation tokens, and the cost in micro-dollars.
 */
public final class ObservedLlmPort implements LlmPort {

    public static final String NAME = "dispute.llm.call";

    private final LlmPort delegate;
    private final ObservationRegistry observations;

    public ObservedLlmPort(LlmPort delegate, ObservationRegistry observations) {
        this.delegate = delegate;
        this.observations = observations;
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        Observation observation = Observation.createNotStarted(NAME, observations)
                .contextualName("llm " + request.model())
                .lowCardinalityKeyValue("model", request.model())
                .lowCardinalityKeyValue("effort", String.valueOf(request.effort()).toLowerCase())
                .lowCardinalityKeyValue("stop_reason", "none")
                .lowCardinalityKeyValue("kind", "none")
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            LlmResponse response = delegate.send(request);
            observation.lowCardinalityKeyValue("stop_reason", response.stopReason().name().toLowerCase())
                    .highCardinalityKeyValues(io.micrometer.common.KeyValues.of(
                            KeyValue.of("answered_model", String.valueOf(response.model())),
                            KeyValue.of("tokens.input", Long.toString(response.usage().inputTokens())),
                            KeyValue.of("tokens.output", Long.toString(response.usage().outputTokens())),
                            KeyValue.of("tokens.cache_read", Long.toString(response.usage().cacheReadInputTokens())),
                            KeyValue.of("tokens.cache_creation",
                                    Long.toString(response.usage().cacheCreationInputTokens())),
                            KeyValue.of("cost.micro_usd", Long.toString(response.cost().microUsd()))));
            return response;
        } catch (LlmCallException e) {
            observation.lowCardinalityKeyValue("kind", e.kind().name().toLowerCase()).error(e);
            throw e;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("kind", "other").error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
