package dev.disputeagent.investigator.application.port.llm;

/**
 * Token usage for one call. A zero {@code cacheReadInputTokens} on a turn that should hit the cache means
 * something invalidated the prefix.
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheCreationInputTokens) {

    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadInputTokens < 0 || cacheCreationInputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
