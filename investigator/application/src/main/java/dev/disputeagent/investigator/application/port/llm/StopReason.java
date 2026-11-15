package dev.disputeagent.investigator.application.port.llm;

/** Why the model stopped. The agent loop branches on this before reading content. */
public enum StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    STOP_SEQUENCE,
    PAUSE_TURN,
    /** Declined by a safety classifier. The case escalates to a human. */
    REFUSAL,
    MODEL_CONTEXT_WINDOW_EXCEEDED
}
