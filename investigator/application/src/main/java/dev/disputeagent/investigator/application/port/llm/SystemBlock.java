package dev.disputeagent.investigator.application.port.llm;

import java.util.Objects;

/**
 * A system prompt block. The stable block carries the cache breakpoint; nothing volatile (timestamps, case data)
 * belongs in a system block.
 */
public record SystemBlock(String text, boolean cacheBreakpoint) {

    public SystemBlock {
        Objects.requireNonNull(text, "text");
    }
}
