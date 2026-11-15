package dev.disputeagent.investigator.application.port.llm;

/**
 * The only way the application reaches a model (ADR 0001).
 *
 * <p>Implementations: {@code AnthropicAdapter} (live), {@code RecordingAdapter} (live, writes fixtures keyed by a hash
 * of the canonicalised request) and {@code ReplayAdapter} (fixtures only; a miss is a failure, never a live call).
 * Implementations make one non-streaming call per invocation and do not retry unless they are the single place
 * retries are configured.
 */
public interface LlmPort {

    /**
     * Sends one conversation turn and returns the model's response.
     *
     * <p>Callers must check {@link LlmResponse#stopReason()} before reading the content; a
     * {@link StopReason#REFUSAL} escalates the case.
     */
    LlmResponse send(LlmRequest request);
}
