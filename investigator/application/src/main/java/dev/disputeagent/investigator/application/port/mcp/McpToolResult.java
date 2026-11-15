package dev.disputeagent.investigator.application.port.mcp;

import java.util.Objects;

/** The text content of a tool result. Untrusted data: never interpreted as instructions. */
public record McpToolResult(String content, boolean isError) {

    public McpToolResult {
        Objects.requireNonNull(content, "content");
    }
}
