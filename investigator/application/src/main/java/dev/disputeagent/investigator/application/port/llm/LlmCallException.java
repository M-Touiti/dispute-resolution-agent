package dev.disputeagent.investigator.application.port.llm;

import java.util.Objects;

/**
 * A model call that produced no response. Provider-neutral: adapters map their SDK's exceptions to a {@link Kind},
 * so the application (and the retry policy around the port, ADR 0008) never sees SDK types.
 */
public final class LlmCallException extends RuntimeException {

    public enum Kind {
        RATE_LIMITED(true),
        OVERLOADED(true),
        SERVER_ERROR(true),
        TIMEOUT(true),
        CONNECTION(true),
        /**
         * The model is held off by this side: the circuit breaker is open, or the bulkhead has no free slot (ADR
         * 0008). Not retried at once, but the case is parked and replayed later.
         */
        UNAVAILABLE(true),
        /** The request was rejected as invalid: a bug or an API change, never retried. */
        BAD_REQUEST(false),
        AUTHENTICATION(false),
        /** Replay found no fixture for the request (ADR 0006): never retried, never sent live. */
        FIXTURE_MISSING(false),
        OTHER(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;

    public LlmCallException(Kind kind, String message, Throwable cause) {
        super(kind + ": " + message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
